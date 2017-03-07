import groovy.io.FileType
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.jetbrains.teamcity.rest.*


LinkedHashMap<String, String> platformMatrix = ["win": "exe",
                                                "unix": "tar.gz",
                                                "mac": "sit"]

Map<String, String> map = evaluate(Arrays.toString(args)) as Map<String, String>
println(sprintf("Args: $map"))

/**
 * @value product   Product name, should be specified just like it is specified in installer:
 *                  "pycharm", "idea", "PhpStorm", etc
 */
product = map.product

os = map.platform
if (platformMatrix.containsKey(os)){
    extension = platformMatrix.get(os)
} else {
    throw new RuntimeException(sprintf("Wrong os: $map.platform"))
}

/**
 * @value buildConfigurationID     TeamCity's buildConfigurationID, like "ijplatform_master_PyCharm",
 *                                 "ijplatform_master_Idea", "ijplatform_master_PhpStorm", etc.
 */
buildConfigurationID = map.buildConfigurationID

/**
 * @value timeout  Timeout in seconds, used for Windows installation and patching processes.
 *                 By default it's 30 seconds for Windows installation, but for patching it's multiplied by 2.
 *                 Can be passed as a parameter via TeamCity for reasons like slow installation or patching in different IDEs.
 */
timeout = map.timeout.toInteger()


class Temp{
    static String folderName = 'temp'

    static concatenateWithFolder(String folder){
        return ((folderName) ? folderName + '/' : '') + folder
    }

    static pathForConcatenatedWithFolder(String folder){
        return Paths.get(concatenateWithFolder(folder))
    }
}

/**
 * This is a Build class
 * @param binding               Global variables.
 * @param buildFolder           This is a path to folder, where build is placed. There is a difference with
 *                              installationFolder: buildFolder is a folder where build is stored exactly and
 *                              installationFolder is a folder where build is installed. It can be like:
 *                              <installationFolder> prev
 *                              ..
 *                              └── prev <installationFolder>
 *                                  └── pycharm-172.339 <buildFolder>
 *                                      ├── bin
 *                                      ├── build.txt
 *                                      ├── debug-eggs
 *                                      ├── help
 *                                      ├── helpers
 *                                      ├── Install-Linux-tar.txt
 *                                      ├── jre
 *                                      ├── lib
 *                                      ├── license
 *                                      └── plugins
 * @param checksumFolder        This is a path to folder, where build checksum is counted.
 * @param edition               Two-letter code like "PC" (PyCharm Community Edition) or "PY" (PyCharm Professional
 *                              edition) should be specified here if any. If there is no editions - empty string
 *                              should be specified then.
 * @param installationFolder    This is folder for build installation. Check buildFolder param for more info.
 * @param installerName         Installer name, like "pycharmPY-171.3566.25.exe" or "pycharmPC-171.3566.25.tar.gz".
 * @param buildNumber           This is a build number, like "171.2342.5" or "171.3566.25".
 * @param log4j                 File 'log4j.jar' that used for patching process.
 */
class Build{
    Binding binding
    Path buildFolder
    Path checksumFolder
    String edition
    Path installationFolder
    String installerName
    String buildNumber
    File log4j

    /**
     * This is a Build constructor
     * @param edition           Two-letter code like "PC" (PyCharm Community Edition) or "PY" (PyCharm Professional
     *                          edition) should be specified here if any. If there is no editions - empty string
     *                          should be specified then.
     * @param buildNumber       This is a build number, like "171.2342.5".
     * @param binding           Global variables.
     * @param withBundledJdk    Is this build with included jdk or not, default is true.
     */
    Build(String edition, String buildNumber, Binding binding, boolean withBundledJdk=true){
        this.edition = edition
        this.buildNumber = buildNumber
        this.binding = binding
        this.installerName = sprintf('%s%s-%s%s.%s', [binding.product,
                                                      edition,
                                                      buildNumber,
                                                      (withBundledJdk) ? '' : '-no-jdk',
                                                      binding.extension])
        this.checksumFolder = Temp.pathForConcatenatedWithFolder("checksums")
    }

    File getInstallerPath(){
        return new File((Temp.folderName) ? Temp.folderName as String : '', installerName)
    }

    String calcChecksum(){
        AntBuilder ant = new AntBuilder()
        ant.mkdir(dir: this.checksumFolder)
        ant.checksum(todir: this.checksumFolder, totalproperty: 'sum'){
            fileset(dir: this.buildFolder){
                if (binding.os=='win') {
                    exclude(name: "**\\Uninstall.exe")
                    exclude(name: "**\\classes.jsa")
                }
            }
        }
        ant.delete(dir: this.checksumFolder)
        ant.echo("Checksum is $ant.project.properties.sum")
        return ant.project.properties.sum
    }

    void downloadBuild(){
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

    void install(Path toFolder){
        AntBuilder ant = new AntBuilder()
        ant.mkdir(dir: toFolder.toString())
        this.installationFolder = toFolder
        File pathToInstaller = this.getInstallerPath()

        switch (binding.os) {
            case 'win':
                ant.exec(executable: "cmd", failonerror: "True") {
                    arg(line: "/k $pathToInstaller /S /D=$installationFolder.absolutePath && ping 127.0.0.1 -n $binding.timeout > nul")
                }
                buildFolder = installationFolder
                break
            case 'unix':
                ant.gunzip(src: pathToInstaller)
                this.installerName = installerName[0..-1-".gz".length()]
                ant.untar(src: this.getInstallerPath(), dest: this.installationFolder)

                defineBuildFolder(installationFolder, 1)
                break
            case 'mac':
                println("##teamcity[blockOpened name='unzip output']")
                ant.exec(executable: "unzip", failonerror: "True"){
                    arg(line: "$pathToInstaller -d $installationFolder")
                }
                println("##teamcity[blockClosed name='unzip output']")

                defineBuildFolder(installationFolder, 2)
                break
            default:
                throw new RuntimeException(sprintf("Wrong os: $binding.os"))
        }
    }

    void defineBuildFolder(Path folder, int depth=1){
        assert depth > 0
        assert Files.list(folder).count() == 1

        if ( depth == 1 ){
            folder.eachDir { directory ->
                this.buildFolder = Paths.get(directory.toString())
            }
        } else if ( depth > 1 ){
            folder.eachDir { directory ->
                findBuildFolder(Paths.get(directory.toString()), depth - 1)
            }
        }
    }

    void patch(File patch){
        this.buildFolder.toFile().eachFileRecurse(FileType.FILES) { file ->
            if (file.name.contains('log4j.jar')) {
                this.log4j = file
            }
        }
        if (!this.log4j){
            throw new RuntimeException("log4j.jar wasn't found")
        }

        AntBuilder ant = new AntBuilder()
        org.apache.tools.ant.types.Path classpath = ant.path {
            pathelement(path: patch)
            pathelement(path: this.log4j)
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
        List<String> splitz = patchName.split('-')

        Boolean withBundledJdk = (!patchName.contains('no-jdk'))
        String testName = sprintf("%s%s edition test, patch name: %s", [splitz.get(0),
                                                                        (withBundledJdk) ? '' : ' (no-jdk)',
                                                                        patchName])
        println(sprintf("##teamcity[testStarted name='%s']", testName))

        try {
            Build prev = new Build(splitz.get(0), splitz.get(1), binding, withBundledJdk)
            Build curr = new Build(splitz.get(0), splitz.get(2), binding, withBundledJdk)

            prev.downloadBuild()
            prev.install(Temp.pathForConcatenatedWithFolder('prev'))
            prev.calcChecksum()
            prev.patch(patch)
            String prev_checksum = prev.calcChecksum()

            curr.downloadBuild()
            curr.install(Temp.pathForConcatenatedWithFolder('curr'))
            String curr_checksum = prev.calcChecksum()

            if (prev_checksum != curr_checksum){
                println(sprintf("##teamcity[testFailed name='%s'] message='Checksums are different: %s and %s']",
                        [testName, prev_checksum, curr_checksum]))
            }
        }
        catch (e) {
            println(sprintf("##teamcity[testFailed name='%s'] message='Exception: %s']", [testName, e]))
            e.printStackTrace()
        } finally {
            println(sprintf("##teamcity[testFinished name='%s']", testName))
            AntBuilder ant = new AntBuilder()
            ant.delete(dir: Temp.folderName)
        }
    }
    println("##teamcity[testSuiteFinished name='Patch Update Autotest']")
}

main()