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

    List<String> extensions() {
        switch (this) {
            case WIN:
                return ['exe', 'zip']
            case LINUX:
                return ["tar.gz"]
            case MAC:
                return ["sit"]
            default:
                throw new IllegalArgumentException("Wrong os: $this")
        }
    }
}


class Globals {
    /**
     * @value product                Product name, should be specified just like it is specified in installer:
     *                               "pycharm", "idea", "PhpStorm", etc
     * @value os {@link OS}
     * @value extensions             Extension values of installers, that should be tested. Can be "exe", "zip"
     *                               (win.zip for IDEA), "tar.gz" or "sit" according to OS
     * @value buildConfigurationIDs  List of TeamCity's buildConfigurationID, like "ijplatform_master_PyCharm",
     *                               "ijplatform_master_Idea", "ijplatform_master_PhpStorm", etc.
     * @value timeout                Timeout in seconds, used for Windows installation (using .exe) and patching
     *                               processes. By default it's 60 seconds for Windows installation, but for patching
     *                               it's multiplied by 3. Can be passed as a parameter via TeamCity for reasons like
     *                               slow installation or patching in different IDEs.
     * @value out                    Folder for artifacts that should be saved after test. Used for store patch logs.
     */
    String product
    OS os
    List<String> extensions
    List<String> buildConfigurationIDs
    Integer timeout
    Path out
    Path tempDirectory

    Globals(Map<String, String> map) {
        product = map.product
        os = OS.fromPatch(map.platform)
        /**
        * @value customExtensions    List of custom extensions, passed through build configuration
        */
        extensions = map.customExtensions ? map.customExtensions.split(';') as List<String> : os.extensions()
        buildConfigurationIDs = map.buildConfigurationID.split(';')
        timeout = map.timeout.toInteger()
        out = Paths.get(map.out)
        tempDirectory = Files.createTempDirectory('patchtest_')
    }

}


class WrongConfigurationException extends RuntimeException {
    WrongConfigurationException(String message)
    {
        super(message)
    }
}


class Installer {
    private Globals globals
    String buildNumber
    String extension
    String installerName

    /**
     * This is an Installer constructor
     * @param buildNumber       This is a build number, like "171.2342.5".
     * @param edition           Two-letter code like "PC" (PyCharm Community Edition) or "PY" (PyCharm Professional
     *                          edition) should be specified here if product has more than 1 configuration.
     *                          If there is no editions - empty string should be specified then.
     * @param extension         Extension value of installer, can be "exe", "zip" (win.zip for IDEA), "tar.gz" or
     *                          "sit" according to OS
     * @param globals           Global variables.
     * @param withBundledJdk    Is this build with included jdk or not, default is true.
     */
    Installer(String buildNumber, String edition, String extension, Globals globals, boolean withBundledJdk = true) {
        this.globals = globals
        this.buildNumber = buildNumber
        this.extension = extension
        this.installerName = "${globals.product}${edition}-${buildNumber}${(withBundledJdk) ? '' : '-no-jdk'}.${extension}"
    }

    private File getInstallerPath(String installer = installerName) {
        return new File(globals.tempDirectory.toString(), installer)
    }

    private void download() {
        BuildArtifact artifact = null
        globals.buildConfigurationIDs.each { String buildConfigurationID ->
            ArrayList<org.jetbrains.teamcity.rest.Build> builds = TeamCityInstance["Companion"]
                    .guestAuth("http://buildserver.labs.intellij.net")
                    .builds()
                    .fromConfiguration(new BuildConfigurationId(buildConfigurationID))
                    .list()
            builds.each { build ->
                if (build.buildNumber == buildNumber) {
                    println(build.toString())

                    List<BuildArtifact> artifacts = build.getArtifacts("")
                    artifact = getArtifact(artifacts)

                    new AntBuilder().echo("Found $artifact.fileName, downloading")
                    artifact.download(getInstallerPath().getAbsoluteFile())
                    return true
                }
            }
        }
        if (!artifact) {
            throw new WrongConfigurationException("Didn't find build $buildNumber in configurations: $globals.buildConfigurationIDs")
        }
    }

    private BuildArtifact getArtifact(List<BuildArtifact> artifacts) {
        String artifactNamePattern = null
        if (artifacts.count { it.fileName == installerName } == 1) {
            artifactNamePattern = installerName
        } else {
            String regex = installerName.replace(buildNumber, '[\\d.]+')
            new AntBuilder().echo("Searching for artifact with $regex regex")

            if (artifacts.count { it.fileName ==~ regex } == 1) {
                artifactNamePattern = regex
            }
        }
        if (!artifactNamePattern){
            throw new RuntimeException("Didn't find suitable installer in artifacts: $artifacts")
        }
        return artifacts.findAll { it.fileName =~ artifactNamePattern }[0]
    }

    private Path install(Path installationFolder) {
        AntBuilder ant = new AntBuilder()
        println('')
        ant.echo("Installing $installerName")
        ant.mkdir(dir: installationFolder.toString())
        File pathToInstaller = getInstallerPath()

        switch (extension) {
            case 'exe':
                ant.exec(executable: "cmd", failonerror: "True") {
                    arg(line: "/k $pathToInstaller /S /D=$installationFolder.absolutePath && ping 127.0.0.1 -n $globals.timeout > nul")
                }
                break
            case ['zip', 'win.zip']:
                ant.unzip(src: pathToInstaller, dest: installationFolder)
                break
            case 'tar.gz':
                ant.gunzip(src: pathToInstaller)
                String tar = installerName[0..-1 - ".gz".length()]
                ant.untar(src: getInstallerPath(tar), dest: installationFolder)
                ant.delete(file: getInstallerPath(tar).getAbsoluteFile())
                break
            case 'sit':
                println("##teamcity[blockOpened name='unzip output']")
                ant.exec(executable: "unzip", failonerror: "True") {
                    arg(line: "$pathToInstaller -d $installationFolder")
                }
                println("##teamcity[blockClosed name='unzip output']")
                break
            default:
                throw new IllegalArgumentException("Wrong extension: $extension")
        }

        switch (globals.os) {
            case OS.WIN:
                return installationFolder
            case OS.LINUX:
                return getBuildFolder(installationFolder, 1)
            case OS.MAC:
                return getBuildFolder(installationFolder, 2)
            default:
                throw new IllegalArgumentException("Wrong os: $globals.os")
        }
    }

    private Path getBuildFolder(Path folder, int depth = 1) {
        List<Path> filesInside = Files.list(folder).collect(Collectors.toList())
        if (filesInside.size() != 1) {
            throw new IllegalArgumentException("Unexpected number of files - $filesInside.size")
        }

        if (depth == 1) {
            return filesInside[0]
        } else if (depth > 1) {
            return getBuildFolder(filesInside[0], depth - 1)
        } else {
            throw new IllegalArgumentException("depth should be positive - $depth")
        }
    }

    Build installBuild(Path installationFolder) {
        download()
        Path buildFolder = install(installationFolder)
        return new Build(buildFolder, globals)
    }

    void delete(){
        new AntBuilder().delete(file: getInstallerPath().getAbsoluteFile())
    }

}


class Build {
    private Globals globals
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
     * @param globals       Global variables.
     */
    Build(Path buildFolder, Globals globals) {
        this.buildFolder = buildFolder
        this.globals = globals
    }

    String calcChecksum() {
        Path checksumFolder = globals.tempDirectory.resolve("checksums")

        AntBuilder ant = new AntBuilder()
        println('')
        ant.echo("Calculating checksum")
        ant.mkdir(dir: checksumFolder)
        ant.checksum(todir: checksumFolder, totalproperty: 'sum') {
            fileset(dir: buildFolder) {
                if (globals.os == OS.WIN) {
                    exclude(name: "**\\Uninstall.exe")
                    exclude(name: "**\\classes.jsa")
                } else if (globals.os == OS.MAC){
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
        buildFolder.resolve('lib').toFile().eachFileRecurse(FileType.FILES) { file ->
            if (file.name == 'log4j.jar') {
                log4jJar = file
            }
        }
        if (!log4jJar) {
            throw new RuntimeException("log4j.jar wasn't found")
        }

        AntBuilder ant = new AntBuilder()
        println('')
        ant.echo("Applying patch $patch.name")
        Path out = globals.out.resolve(patch.name)
        ant.mkdir(dir: out)

        ant.java(classpath: ant.path {pathelement(path: patch); pathelement(path: log4jJar)},
                 classname: "com.intellij.updater.Runner",
                 fork: "true",
                 maxmemory: "800m",
                 timeout: globals.timeout * 3000,
                 resultproperty: 'patchResult') {
            jvmarg(value: "-Didea.updater.log=$out")
            arg(line: "install '$buildFolder'")
        }
        switch (ant.project.properties.patchResult){
            case '42':
                ant.echo("Message 'Java Result: 42' is OK, because this error is thrown from GUI " +
                        "and it means that IDE restart is needed")
                break
            case '-1':
                throw new RuntimeException("Patch process failed with -1 result")
            default:
                ant.echo("$ant.project.properties.patchResult java result is unexpected here, please check")
        }
    }

    void delete(){
        new AntBuilder().delete(dir: buildFolder.toString())
    }
}


static ArrayList<File> findFiles(String extension, File directory = new File('.')) {
    ArrayList<File> list = []
    directory.eachFileRecurse(FileType.FILES) { file ->
        if (file.name.endsWith(extension)) {
            println(file)
            list << file
        }
    }
    return list
}


def runTest(Map<String, String> map, String dir = 'patches') {
    Globals globals = new Globals(map)

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

        String product = globals.product.substring(0, 1).toUpperCase() + globals.product.substring(1)
        String testName = "${product} ${edition}${withBundledJdk ? '' : ' (no-jdk)'}${(edition || !withBundledJdk) ? ' edition' : ''} test, patch name: $patchName"
        println("##teamcity[testStarted name='$testName']")

        try {
            new AntBuilder().mkdir(dir: globals.tempDirectory.toString())
            for (extension in globals.extensions) {
                println((globals.extensions.size() > 1) ? "##teamcity[blockOpened name='$extension installers']" : '\n')
                Installer prevInstaller = new Installer(partsOfPatchName.get(1), edition, extension, globals, withBundledJdk)
                Build prevBuild = prevInstaller.installBuild(globals.tempDirectory.resolve("previous-${partsOfPatchName.get(0)}-${partsOfPatchName.get(1)}-$extension"))
                prevBuild.calcChecksum()
                prevBuild.patch(patch)
                String prevChecksum = prevBuild.calcChecksum()
                println('')

                Installer currInstaller = new Installer(partsOfPatchName.get(2), edition, extension, globals, withBundledJdk)
                Build currBuild = currInstaller.installBuild(globals.tempDirectory.resolve("current-${partsOfPatchName.get(0)}-${partsOfPatchName.get(2)}-$extension"))
                String currChecksum = currBuild.calcChecksum()

                if (prevChecksum != currChecksum) {
                    println("##teamcity[testFailed name='$testName'] message='Checksums are different: $prevChecksum and $currChecksum']")
                    break
                }
                println("\nBuild checksums of $extension installers are ${(prevChecksum == currChecksum)? 'equal' : 'different'}: $prevChecksum and $currChecksum\n")

                prevInstaller.delete()
                currInstaller.delete()
                prevBuild.delete()
                currBuild.delete()
                (globals.extensions.size() > 1) ? println("##teamcity[blockClosed name='$extension installers']") : null
            }
        }
        catch (WrongConfigurationException e) {
            println("##teamcity[testIgnored name='$testName'] message='Check your build configuration: $e.message']")
        }
        catch (e){
            println("##teamcity[testFailed name='$testName'] message='$e']")
            e.printStackTrace()
        } finally {
            new AntBuilder().delete(dir: globals.tempDirectory.toString())
            println("##teamcity[testFinished name='$testName']")
        }
    }
    println("##teamcity[testSuiteFinished name='Patch Update Autotest']")
}


Map<String, String> map = evaluate(Arrays.toString(args)) as Map<String, String>
println("Args: $map")
runTest(map)