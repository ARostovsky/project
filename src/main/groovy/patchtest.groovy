import groovy.io.FileType
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.jetbrains.teamcity.rest.*

enum OS {
    WIN,
    LINUX,
    MAC

    static fromPatch(String name) {
        return name == "unix" ? LINUX : valueOf(name.toUpperCase())
    }

    def extension() {
        switch (this) {
            case WIN:
                return "exe"
            case LINUX:
                return "tar.gz"
            case MAC:
                return "sit"
        }
    }
}

Map<String, String> map = evaluate(Arrays.toString(args)) as Map<String, String>
println(sprintf("Args: $map"))

/**
 * @value product               Product name, should be specified just like it is specified in installer:
 *                              "pycharm", "idea", "PhpStorm", etc
 *
 * @value os                    enum value of OS, can be OS.WIN, OS.LINUX, OS.MAC
 *
 * @value extension             extension value of installer, can be "exe", "tar.gz" or "sit" according to OS
 *
 * @value buildConfigurationID  TeamCity's buildConfigurationID, like "ijplatform_master_PyCharm",
 *                              "ijplatform_master_Idea", "ijplatform_master_PhpStorm", etc.
 *
 * @value timeout               Timeout in seconds, used for Windows installation and patching processes.
 *                              By default it's 30 seconds for Windows installation, but for patching it's multiplied by 2.
 *                              Can be passed as a parameter via TeamCity for reasons like slow installation or patching in different IDEs.
 */
product = map.product
os = OS.fromPatch(map.platform)
extension = os.extension()
buildConfigurationID = map.buildConfigurationID
timeout = map.timeout.toInteger()
tempDirectory = Files.createTempDirectory('patchtest_')


class Installer{
    private Binding binding
    String buildNumber
    String installerName

    /**
     * This is an Installer constructor
     * @param edition           Two-letter code like "PC" (PyCharm Community Edition) or "PY" (PyCharm Professional
     *                          edition) should be specified here if any. If there is no editions - empty string
     *                          should be specified then.
     * @param buildNumber       This is a build number, like "171.2342.5".
     * @param binding           Global variables.
     * @param withBundledJdk    Is this build with included jdk or not, default is true.
     */
    Installer(String edition, String buildNumber, Binding binding, boolean withBundledJdk=true){
        this.binding = binding
        this.buildNumber = buildNumber
        this.installerName = sprintf('%s%s-%s%s.%s', [binding.product,
                                                      edition,
                                                      buildNumber,
                                                      (withBundledJdk) ? '' : '-no-jdk',
                                                      binding.extension])
    }

    File getInstallerPath(String installer=installerName){
        return new File(binding.tempDirectory.toString(), installer)
    }

    private void download(){
        ArrayList<org.jetbrains.teamcity.rest.Build> builds = TeamCityInstance["Companion"]
                .guestAuth("http://buildserver.labs.intellij.net")
                .builds()
                .fromConfiguration(new BuildConfigurationId(binding.buildConfigurationID))
                .list()
        builds.each { build ->
            if (build.buildNumber == this.buildNumber){
                println(build)
                build.downloadArtifact(installerName, this.getInstallerPath().getAbsoluteFile())
                return true
            }
        }
    }

    private Path install(Path installationFolder){
        AntBuilder ant = new AntBuilder()
        ant.mkdir(dir: installationFolder.toString())
        File pathToInstaller = this.getInstallerPath()

        switch (binding.os) {
            case OS.WIN:
                ant.exec(executable: "cmd", failonerror: "True") {
                    arg(line: "/k $pathToInstaller /S /D=$installationFolder.absolutePath && ping 127.0.0.1 -n $binding.timeout > nul")
                }
                return installationFolder
            case OS.LINUX:
                ant.gunzip(src: pathToInstaller)
                String tar = installerName[0..-1-".gz".length()]
                ant.untar(src: this.getInstallerPath(tar), dest: installationFolder)

                return getBuildFolder(installationFolder, 1)
            case OS.MAC:
                println("##teamcity[blockOpened name='unzip output']")
                ant.exec(executable: "unzip", failonerror: "True"){
                    arg(line: "$pathToInstaller -d $installationFolder")
                }
                println("##teamcity[blockClosed name='unzip output']")

                return getBuildFolder(installationFolder, 2)
            default:
                throw new RuntimeException(sprintf("Wrong os: $binding.os"))
        }
    }

    private Path getBuildFolder(Path folder, int depth=1){
        assert depth > 0
        assert Files.list(folder).count() == 1
        Path buildFolder = null

        if ( depth == 1 ){
            folder.eachDir { directory ->
                buildFolder = Paths.get(directory.toString())
            }
        } else if ( depth > 1 ){
            folder.eachDir { directory ->
                buildFolder = getBuildFolder(Paths.get(directory.toString()), depth - 1)
            }
        }

        if (!buildFolder){
            throw new RuntimeException("buildFolder's path wasn't found")
        }

        return buildFolder
    }

    Build installBuild(Path installationFolder){
        download()
        Path buildFolder = install(installationFolder)
        return new Build(buildFolder, binding)
    }

}



class Build{
    private Binding binding
    Path buildFolder
    private File log4jJar

    /**
     * This is a Build class
     * @param buildFolder           This is a path to folder, where build is placed. There is a difference with
     *                              installation folder: buildFolder is a directory where 'bin', 'lib' and 'plugins' folders
     *                              are located and installation folder is a folder where build is installed/unpacked by
     *                              installer or archive manager. It can be like:
     *                              ...
     *                              └── prev <installation folder>
     *                                  └── pycharm-172.339 <buildFolder>
     *                                      ├── bin
     *                                      ├── lib
     *                                      ├── plugins
     *                                      └── ...
     * @param binding               Global variables.
     */
    Build(Path buildFolder, Binding binding){
        this.buildFolder = buildFolder
        this.binding = binding

    }

    String calcChecksum(){
        Path checksumFolder = Paths.get(binding.tempDirectory.toString(), "checksums")

        AntBuilder ant = new AntBuilder()
        ant.mkdir(dir: checksumFolder)
        ant.checksum(todir: checksumFolder, totalproperty: 'sum'){
            fileset(dir: this.buildFolder){
                if (binding.os==OS.WIN) {
                    exclude(name: "**\\Uninstall.exe")
                    exclude(name: "**\\classes.jsa")
                }
            }
        }
        ant.delete(dir: checksumFolder)
        ant.echo("Checksum is $ant.project.properties.sum")
        return ant.project.properties.sum
    }

    void patch(File patch){
        buildFolder.toFile().eachFileRecurse(FileType.FILES) { file ->
            if (file.name.contains('log4j.jar')) {
                this.log4jJar = file
            }
        }
        if (!this.log4jJar){
            throw new RuntimeException("log4j.jar wasn't found")
        }

        AntBuilder ant = new AntBuilder()
        org.apache.tools.ant.types.Path classpath = ant.path {
            pathelement(path: patch)
            pathelement(path: this.log4jJar)
        }
        ant.java(classpath: "${classpath}",
                classname: "com.intellij.updater.Runner",
                fork: "true",
                maxmemory: "800m",
                timeout: binding.timeout * 2000){
            arg(line: "install '$buildFolder'")
        }
    }
}


static ArrayList<File> findFiles (String mask, File directory=File('.')) {
    ArrayList<File> list = []
    directory.eachFileRecurse (FileType.FILES) { file ->
        if (file.name.contains(mask)) {
            println(file)
            list << file
        }
    }
    return list
}

def main(String dir='patches'){
    ArrayList<File> patches = findFiles(mask='.jar', directory=new File(dir))
    println("##teamcity[enteredTheMatrix]")
    println("##teamcity[testCount count='$patches.size']")
    println("##teamcity[testSuiteStarted name='Patch Update Autotest']")

    patches.each { File patch ->
        String patchName = patch.getName()
        List<String> partsOfPatchName = patchName.split('-')

        boolean withBundledJdk = (!patchName.contains('no-jdk'))
        String testName = sprintf("%s%s edition test, patch name: %s", [partsOfPatchName.get(0),
                                                                        (withBundledJdk) ? '' : ' (no-jdk)',
                                                                        patchName])
        println(sprintf("##teamcity[testStarted name='%s']", testName))

        try {
            Installer prevInstaller = new Installer(partsOfPatchName.get(0), partsOfPatchName.get(1), binding, withBundledJdk)
            Build prevBuild = prevInstaller.installBuild(Paths.get(tempDirectory.toString(), "prev"))
            prevBuild.calcChecksum()
            prevBuild.patch(patch)
            String prevChecksum = prevBuild.calcChecksum()

            Installer currInstaller = new Installer(partsOfPatchName.get(0), partsOfPatchName.get(2), binding, withBundledJdk)
            Build currBuild = currInstaller.installBuild(Paths.get(tempDirectory.toString(), "curr"))
            String currChecksum = currBuild.calcChecksum()

            if (prevChecksum != currChecksum){
                println(sprintf("##teamcity[testFailed name='%s'] message='Checksums are different: %s and %s']",
                        [testName, prevChecksum, currChecksum]))
            }
        }
        catch (e) {
            println(sprintf("##teamcity[testFailed name='%s'] message='Exception: %s']", [testName, e]))
            e.printStackTrace()
        } finally {
            println(sprintf("##teamcity[testFinished name='%s']", testName))
            AntBuilder ant = new AntBuilder()
            ant.delete(dir: tempDirectory.toString())
        }
    }
    println("##teamcity[testSuiteFinished name='Patch Update Autotest']")
}

main()