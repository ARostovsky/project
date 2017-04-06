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

    def extension(binding) {
        switch (this) {
            case WIN:
                return binding.winExtension
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
 * @value winExtension          Extension for windows installers, can be "zip" (by default) or "exe"
 *
 * @value extension             Extension value of installer, can be "exe"/"zip", "tar.gz" or "sit" according to OS
 *
 * @value buildConfigurationIDs List of TeamCity's buildConfigurationID, like "ijplatform_master_PyCharm",
 *                              "ijplatform_master_Idea", "ijplatform_master_PhpStorm", etc.
 *
 * @value timeout               Timeout in seconds, used for Windows installation (using .exe) and patching processes.
 *                              By default it's 60 seconds for Windows installation, but for patching it's multiplied
 *                              by 3. Can be passed as a parameter via TeamCity for reasons like slow installation or
 *                              patching in different IDEs.
 *
 * @value out                   Folder for artifacts that should be saved after test. Used for store patch logs.
 *
 */
product = map.product
os = OS.fromPatch(map.platform)
winExtension =  map.winExtension
extension = os.extension(binding)
buildConfigurationIDs = map.buildConfigurationID.split(';')
timeout = map.timeout.toInteger()
out = Paths.get(map.out)
tempDirectory = Files.createTempDirectory('patchtest_')


class WrongConfigurationException extends RuntimeException
{
    WrongConfigurationException(String message)
    {
        super(message);
    }
}


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
        BuildArtifact artifact = null
        binding.buildConfigurationIDs.each { String buildConfigurationID ->
            ArrayList<org.jetbrains.teamcity.rest.Build> builds = TeamCityInstance["Companion"]
                    .guestAuth("http://buildserver.labs.intellij.net")
                    .builds()
                    .fromConfiguration(new BuildConfigurationId(buildConfigurationID))
                    .list()
            builds.each { build ->
                if (build.buildNumber == buildNumber) {
                    println("\n" + build.toString())

                    List<BuildArtifact> artifacts = build.getArtifacts("")
                    String artifactNamePattern = getArtifactNamePattern(artifacts)
                    artifact = artifacts.findAll { it.fileName =~ artifactNamePattern }[0]

                    new AntBuilder().echo("Found $artifact.fileName, downloading")
                    artifact.download(getInstallerPath().getAbsoluteFile())
                    return true
                }
            }
        }
        if (!artifact) {
            throw new WrongConfigurationException("Didn't find build $buildNumber in configurations: $binding.buildConfigurationIDs")
        }
    }

    private String getArtifactNamePattern(List<BuildArtifact> artifacts) {
        if (artifacts.count { it.fileName =~ installerName } == 1) {
            return installerName
        } else {
            String regex = installerName.replace(buildNumber, '.[\\d.]+')
            new AntBuilder().echo("Searching for artifact with $regex regex")

            if (artifacts.count { it.fileName =~ regex } == 1) {
                return regex
            }
        }
        throw new RuntimeException("Didn't find suitable installer in artifacts: $artifacts")
    }

    private Path install(Path installationFolder) {
        AntBuilder ant = new AntBuilder()
        println('')
        ant.echo("Installing $installerName")
        ant.mkdir(dir: installationFolder.toString())
        File pathToInstaller = getInstallerPath()

        switch (binding.os) {
            case OS.WIN:
                if (binding.extension == 'exe') {
                    ant.exec(executable: "cmd", failonerror: "True") {
                        arg(line: "/k $pathToInstaller /S /D=$installationFolder.absolutePath && ping 127.0.0.1 -n $binding.timeout > nul")
                    }
                } else if (binding.extension.contains('zip')) {
                    ant.unzip(src: pathToInstaller, dest: installationFolder)
                } else {
                    throw new IllegalArgumentException(sprintf("Wrong extension: $binding.extension"))
                }
                return installationFolder
            case OS.LINUX:
                ant.gunzip(src: pathToInstaller)
                String tar = installerName[0..-1 - ".gz".length()]
                ant.untar(src: getInstallerPath(tar), dest: installationFolder)

                return getBuildFolder(installationFolder, 1)
            case OS.MAC:
                println("##teamcity[blockOpened name='unzip output']")
                ant.exec(executable: "unzip", failonerror: "True") {
                    arg(line: "$pathToInstaller -d $installationFolder")
                }
                println("##teamcity[blockClosed name='unzip output']")

                return getBuildFolder(installationFolder, 2)
            default:
                throw new IllegalArgumentException(sprintf("Wrong os: $binding.os"))
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
            fileset(dir: buildFolder) {
                if (binding.os == OS.WIN) {
                    exclude(name: "**\\Uninstall.exe")
                    exclude(name: "**\\classes.jsa")
                } else if (binding.os == OS.MAC){
                    exclude(name: "**\\*.dylib")
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
        edition = edition in ['IC', 'IU', 'PC', 'PY', 'PE'] ? edition : ''
        edition = (edition == 'PE') ? 'EDU' : edition

        String product = binding.product.substring(0, 1).toUpperCase() + binding.product.substring(1)
        String testName = sprintf("%s %s%s%s test, patch name: %s", [product,
                                                                     edition,
                                                                     (withBundledJdk) ? '' : ' (no-jdk)',
                                                                     (edition || !withBundledJdk) ? ' edition' : '',
                                                                     patchName])
        println(sprintf("##teamcity[testStarted name='%s']", testName))

        try {
            Installer prevInstaller = new Installer(partsOfPatchName.get(1), edition, binding, withBundledJdk)
            Build prevBuild = prevInstaller.installBuild(Paths.get(tempDirectory.toString(), patchName, "prev"))
            prevBuild.calcChecksum()
            prevBuild.patch(patch)
            String prevChecksum = prevBuild.calcChecksum()

            Installer currInstaller = new Installer(partsOfPatchName.get(2), edition, binding, withBundledJdk)
            Build currBuild = currInstaller.installBuild(Paths.get(tempDirectory.toString(), patchName, "curr"))
            String currChecksum = currBuild.calcChecksum()

            if (prevChecksum != currChecksum) {
                println(sprintf("##teamcity[testFailed name='%s'] message='Checksums are different: %s and %s']",
                        [testName, prevChecksum, currChecksum]))
            }
        }
        catch (WrongConfigurationException e) {
            println(sprintf("##teamcity[testIgnored name='%s'] message='Check your build configuration: %s']", [testName, e.message]))
        }
        catch (e){
            println(sprintf("##teamcity[testFailed name='%s'] message='%s']", [testName, e]))
            e.printStackTrace()
        } finally {
            println(sprintf("##teamcity[testFinished name='%s']", testName))
        }
    }
    new AntBuilder().delete(dir: tempDirectory.toString())
    println("##teamcity[testSuiteFinished name='Patch Update Autotest']")
}

main()