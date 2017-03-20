import groovy.io.FileType
import org.jetbrains.teamcity.rest.*

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

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
 * @value os {@link OS}
 *
 * @value extension             extension value of installer, can be "exe", "tar.gz" or "sit" according to OS
 *
 * @value buildConfigurationIDs List of TeamCity's buildConfigurationID, like "ijplatform_master_PyCharm",
 *                              "ijplatform_master_Idea", "ijplatform_master_PhpStorm", etc.
 *
 * @value timeout               Timeout in seconds, used for Windows installation and patching processes.
 *                              By default it's 60 seconds for Windows installation, but for patching it's multiplied
 *                              by 3. Can be passed as a parameter via TeamCity for reasons like slow installation or
 *                              patching in different IDEs.
 *
 * @value out                   Folder for artifacts that should be saved after test. Used for store patch logs.
 *
 */
product = map.product
os = OS.fromPatch(map.platform)
extension = os.extension()
buildConfigurationIDs = map.buildConfigurationID.split(';')
timeout = map.timeout.toInteger()
out = Paths.get(map.out)
tempDirectory = Files.createTempDirectory('patchtest_')


class Installer {
    private Binding binding
    String buildNumber
    String installerName

    /**
     * This is an Installer constructor
     * @param buildNumber       This is a build number, like "171.2342.5".
     * @param edition           Two-letter code like "PC" (PyCharm Community Edition) or "PY" (PyCharm Professional
     *                          edition) should be specified here if product has more than 1 configuration.
     *                          If there is no editions - empty string should be specified then.
     * @param binding           Global variables.
     * @param withBundledJdk    Is this build with included jdk or not, default is true.
     */
    Installer(String buildNumber, String edition, Binding binding, boolean withBundledJdk = true) {
        this.binding = binding
        this.buildNumber = buildNumber
        this.installerName = sprintf('%s%s-%s%s.%s', [binding.product,
                                                      edition,
                                                      buildNumber,
                                                      (withBundledJdk) ? '' : '-no-jdk',
                                                      binding.extension])
    }

    private File getInstallerPath(String installer = installerName) {
        return new File(binding.tempDirectory.toString(), installer)
    }

    private void download() {
        binding.buildConfigurationIDs.each { String buildConfigurationID ->
            ArrayList<org.jetbrains.teamcity.rest.Build> builds = TeamCityInstance["Companion"]
                    .guestAuth("http://buildserver.labs.intellij.net")
                    .builds()
                    .fromConfiguration(new BuildConfigurationId(buildConfigurationID))
                    .list()
            builds.each { build ->
                if (build.buildNumber == this.buildNumber) {
                    println("\n" + build.toString())
                    AntBuilder ant = new AntBuilder()
                    String installerPattern = installerName.replace(this.buildNumber, '*')

                    ant.echo("Searching for artifact with $installerPattern pattern")
                    BuildArtifact artifact = build.findArtifact(installerPattern, "")

                    ant.echo("Found $artifact.fileName, downloading")
                    artifact.download(this.getInstallerPath().getAbsoluteFile())
                    return true
                }
            }
        }
    }

    private Path install(Path installationFolder) {
        AntBuilder ant = new AntBuilder()
        println('')
        ant.echo("Installing $installerName")
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
                String tar = installerName[0..-1 - ".gz".length()]
                ant.untar(src: this.getInstallerPath(tar), dest: installationFolder)

                return getBuildFolder(installationFolder, 1)
            case OS.MAC:
                println("##teamcity[blockOpened name='unzip output']")
                ant.exec(executable: "unzip", failonerror: "True") {
                    arg(line: "$pathToInstaller -d $installationFolder")
                }
                println("##teamcity[blockClosed name='unzip output']")

                return getBuildFolder(installationFolder, 2)
            default:
                throw new RuntimeException(sprintf("Wrong os: $binding.os"))
        }
    }

    private Path getBuildFolder(Path folder, int depth = 1) {
        List<Path> filesInside = Files.list(folder).collect(Collectors.toList())
        if (filesInside.size() != 1) {
            throw new IllegalArgumentException(sprintf("Unexpected number of files - %s", filesInside.size()))
        }

        if (depth == 1) {
            return Paths.get(filesInside[0].toString())
        } else if (depth > 1) {
            return getBuildFolder(Paths.get(filesInside[0].toString()), depth - 1)
        } else {
            throw new IllegalArgumentException(sprintf("depth should be positive - %s", depth))
        }
    }

    Build installBuild(Path installationFolder) {
        download()
        Path buildFolder = install(installationFolder)
        return new Build(buildFolder, binding)
    }

}


class Build {
    private Binding binding
    Path buildFolder

    /**
     * This is a Build constructor
     * @param buildFolder   This is a path to folder, where build is placed. There is a difference with
     *                      installation folder: buildFolder is a directory where 'bin', 'lib' and 'plugins'
     *                      folders are located and installation folder is a folder where build is
     *                      installed/unpacked by installer or archive manager. It can be like:
     *                      ...
     *                      └── prev <installation folder>
     *                          └── pycharm-172.339 <buildFolder>
     *                              ├── bin
     *                              ├── lib
     *                              ├── plugins
     *                              └── ...
     * @param binding       Global variables.
     */
    Build(Path buildFolder, Binding binding) {
        this.buildFolder = buildFolder
        this.binding = binding
    }

    String calcChecksum() {
        Path checksumFolder = Paths.get(binding.tempDirectory.toString(), "checksums")

        AntBuilder ant = new AntBuilder()
        println('')
        ant.echo("Calculating checksum")
        ant.mkdir(dir: checksumFolder)
        ant.checksum(todir: checksumFolder, totalproperty: 'sum') {
            fileset(dir: this.buildFolder) {
                if (binding.os == OS.WIN) {
                    exclude(name: "**\\Uninstall.exe")
                    exclude(name: "**\\classes.jsa")
                }
            }
        }
        ant.delete(dir: checksumFolder)
        ant.echo("Checksum is $ant.project.properties.sum")
        return ant.project.properties.sum
    }

    void patch(File patch) {
        File log4jJar = null
        buildFolder.toFile().eachFileRecurse(FileType.FILES) { file ->
            if (file.name.contains('log4j.jar')) {
                log4jJar = file
            }
        }
        if (!log4jJar) {
            throw new RuntimeException("log4j.jar wasn't found")
        }

        AntBuilder ant = new AntBuilder()
        println('')
        ant.echo("Applying patch $patch.name")
        Path out = Paths.get(binding.out.toString(), patch.name)
        ant.mkdir(dir: out)

        org.apache.tools.ant.types.Path classpath = ant.path {
            pathelement(path: patch)
            pathelement(path: log4jJar)
        }
        ant.java(classpath: "${classpath}",
                 classname: "com.intellij.updater.Runner",
                 fork: "true",
                 maxmemory: "800m",
                 timeout: binding.timeout * 3000) {
            jvmarg(value: "-Didea.updater.log=$out")
            arg(line: "install '$buildFolder'")
        }
        ant.echo("Message 'Java Result: 42' is OK, because this error is thrown from GUI " +
                 "and it means that IDE restart is needed")
    }
}


static ArrayList<File> findFiles(String mask, File directory = File('.')) {
    ArrayList<File> list = []
    directory.eachFileRecurse(FileType.FILES) { file ->
        if (file.name.contains(mask)) {
            println(file)
            list << file
        }
    }
    return list
}

def main(String dir = 'patches') {
    ArrayList<File> patches = findFiles(mask = '.jar', directory = new File(dir))
    println("##teamcity[enteredTheMatrix]")
    println("##teamcity[testCount count='$patches.size']")
    println("##teamcity[testSuiteStarted name='Patch Update Autotest']")

    patches.each { File patch ->
        String patchName = patch.getName()
        List<String> partsOfPatchName = patchName.split('-')
        boolean withBundledJdk = (!patchName.contains('no-jdk'))

        String edition = partsOfPatchName.get(0)
        edition = edition in ['IC', 'IU', 'PC', 'PY'] ? edition : ''

        String product = binding.product.substring(0, 1).toUpperCase() + binding.product.substring(1)
        String testName = sprintf("%s %s%s %s test, patch name: %s", [product,
                                                                      edition,
                                                                      (withBundledJdk) ? '' : ' (no-jdk)',
                                                                      (edition || !withBundledJdk) ? 'edition' : '',
                                                                      patchName])
        println(sprintf("##teamcity[testStarted name='%s']", testName))

        try {
            Installer prevInstaller = new Installer(partsOfPatchName.get(1), edition, binding, withBundledJdk)
            Build prevBuild = prevInstaller.installBuild(Paths.get(tempDirectory.toString(), "prev"))
            prevBuild.calcChecksum()
            prevBuild.patch(patch)
            String prevChecksum = prevBuild.calcChecksum()

            Installer currInstaller = new Installer(partsOfPatchName.get(2), edition, binding, withBundledJdk)
            Build currBuild = currInstaller.installBuild(Paths.get(tempDirectory.toString(), "curr"))
            String currChecksum = currBuild.calcChecksum()

            if (prevChecksum != currChecksum) {
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