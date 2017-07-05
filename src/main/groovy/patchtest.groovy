import groovy.io.FileType
import org.jetbrains.teamcity.rest.*

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING

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
                return ["exe", "zip"]
            case LINUX:
                return ["tar.gz"]
            case MAC:
                return ["sit"]
            default:
                throw new IllegalArgumentException("Wrong os: $this")
        }
    }
}


class Context {
    AntBuilder ant = new AntBuilder()
    /**
     * Set of TeamCity's buildConfigurationID, like "ijplatform_master_PyCharm", "ijplatform_master_Idea",
     * "ijplatform_master_PhpStorm", etc.
     */
    Set<BuildConfigurationId> buildConfigurationIDs
    /**
     * Build containing patches to test
     */
    org.jetbrains.teamcity.rest.Build build
    /**
     * Extension values of installers, that should be tested. Can be "exe", "zip" (win.zip for IDEA),
     * "tar.gz" or "sit" according to OS
     */
    List<String> extensions
    OS os
    /**
     * Folder for artifacts that should be saved after test. Used for store patch logs.
     */
    Path out
    /**
     * Directory to store patches
     */
    String patchesDir
    /**
     * platform value, passed through build configuration
     */
    String platform
    /**
     * Product name, should be specified just like it is specified in installer: "pycharm", "idea", "PhpStorm", etc
     */
    String product
    TeamCityInstance teamCityInstance
    Path tempDirectory
    /**
     * Timeout in seconds, used for Windows installation (using .exe) and patching processes. By default it's
     * 60 seconds for Windows installation, but for patching it's multiplied by 3. Can be passed as a parameter
     * via TeamCity for reasons like slow installation or patching in different IDEs.
     */
    Integer timeout

    Context(Map<String, String> map) {
        buildConfigurationIDs = map.buildConfigurationID
                                   .split(';')
                                   .findAll()
                                   .collect { String it -> new BuildConfigurationId(it)}
                                   .toSet()
        os = OS.fromPatch(map.platform)
        // customExtensions - list of custom extensions, passed through build configuration
        extensions = map.customExtensions ? map.customExtensions.split(';') as List<String> : os.extensions()
        out = Paths.get(map.out)
        patchesDir = 'patches'
        platform = map.platform
        product = map.product
        tempDirectory = Files.createTempDirectory('patchtest_')
        timeout = map.timeout.toInteger()

        teamCityInstance = TeamCityInstance["Companion"]
                .httpAuth("http://buildserver.labs.intellij.net", map.authUserId, map.authPassword)

        // buildId - Build ID of currently running configuration
        build = getSourceBuild(map.buildId)
        updateBuildConfigurationIDs(build)
    }

    private org.jetbrains.teamcity.rest.Build getSourceBuild(String patchTestBuildId) {
        org.jetbrains.teamcity.rest.Build patchTestBuild = teamCityInstance.build(new BuildId(patchTestBuildId))

        TriggeredInfo triggeredInfo = patchTestBuild.fetchTriggeredInfo()

        if (triggeredInfo.build != null) {
            build = triggeredInfo.build
            ant.echo("Patches from triggered build will be downloaded and tested: ")
        } else {
            List<FinishBuildTrigger> finishBuildTriggers = teamCityInstance
                    .buildConfiguration(new BuildConfigurationId(patchTestBuild.buildTypeId))
                    .fetchFinishBuildTriggers()

            if (finishBuildTriggers.isEmpty()) {
                throw new RuntimeException("Current Patch Test configuration doesn't have Finish Build triggers")
            }

            for (trigger in finishBuildTriggers) {
                build = teamCityInstance.builds()
                                        .fromConfiguration(trigger.initiatedBuildConfiguration)
                                        .withAnyStatus()
                                        .latest()

                if (build != null) break
            }

            if (build == null) {
                throw new RuntimeException("All configurations specified as Finish Build triggers don't have any builds")
            }

            ant.echo("Patches from build (id: $build.id.stringId, number: $build.buildNumber) will be downloaded and tested: ")
        }

        ant.echo(build.toString())
        return build
    }

    private void updateBuildConfigurationIDs(org.jetbrains.teamcity.rest.Build build) {
        List<ArtifactDependency> artifactDependencies = teamCityInstance
                .buildConfiguration(new BuildConfigurationId(build.buildTypeId))
                .fetchArtifactDependencies()

        for (artifact in artifactDependencies) {
            buildConfigurationIDs.add(artifact.dependsOnBuildConfiguration.id)
        }
        println(buildConfigurationIDs)
    }
}


class WrongConfigurationException extends RuntimeException {
    WrongConfigurationException(String message) {
        super(message)
    }
}


class KnownException extends RuntimeException {
    KnownException(String message) {
        super(message)
    }
}


class Installer {
    private Context context
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
     * @param withBundledJdk    Is this build with included jdk or not, default is true.
     */
    Installer(Context context, String buildNumber, String edition, String extension, boolean withBundledJdk = true) {
        this.context = context
        this.buildNumber = buildNumber
        this.extension = extension
        this.installerName = "${context.product}${edition}-${buildNumber}${(withBundledJdk) ? '' : '-no-jdk'}.${extension}"
    }

    private File getInstallerPath(String installer = installerName) {
        return new File(context.tempDirectory.toString(), installer)
    }

    private void download() {
        BuildArtifact artifact = null
        for (buildConfigurationID in context.buildConfigurationIDs) {
            org.jetbrains.teamcity.rest.Build build = context.teamCityInstance.build(buildConfigurationID, buildNumber)

            if (build == null) continue
            println(build.toString())

            List<BuildArtifact> artifacts = build.getArtifacts("")
            artifact = getArtifact(artifacts)
            context.ant.echo("Found $artifact.fileName, downloading")
            artifact.download(getInstallerPath().getAbsoluteFile())

            break
        }
        if (artifact == null) {
            throw new WrongConfigurationException("Check your build configuration: Didn't find build $buildNumber in configurations: $context.buildConfigurationIDs")
        }
    }

    private BuildArtifact getArtifact(List<BuildArtifact> artifacts) {
        String artifactNamePattern = null
        if (artifacts.count { it.fileName == installerName } == 1) {
            artifactNamePattern = installerName
        } else {
            String regex = installerName.replace(buildNumber, '(EAP-)?[\\d.]+')
            context.ant.echo("Searching for artifact with $regex regex")

            if (artifacts.count { it.fileName ==~ regex } == 1) {
                artifactNamePattern = regex
            }
        }
        if (artifactNamePattern == null) {
            throw new RuntimeException("Didn't find suitable installer in artifacts: $artifacts")
        }
        return artifacts.findAll { it.fileName =~ artifactNamePattern }[0]
    }

    private Path install(Path installationFolder) {
        println('')
        context.ant.echo("Installing $installerName")
        context.ant.mkdir(dir: installationFolder.toString())
        File pathToInstaller = getInstallerPath()

        switch (extension) {
            case 'exe':
                context.ant.exec(executable: "cmd", failonerror: "True") {
                    arg(line: "/k $pathToInstaller /S /D=$installationFolder.absolutePath && ping 127.0.0.1 -n $context.timeout > nul")
                }
                break
            case ['zip', 'win.zip']:
                context.ant.unzip(src: pathToInstaller, dest: installationFolder)
                break
            case 'tar.gz':
                context.ant.gunzip(src: pathToInstaller)
                String tar = installerName[0..-1 - ".gz".length()]
                context.ant.untar(src: getInstallerPath(tar), dest: installationFolder)
                context.ant.delete(file: getInstallerPath(tar).getAbsoluteFile())
                break
            case 'sit':
                println("##teamcity[blockOpened name='unzip output']")
                context.ant.exec(executable: "unzip", failonerror: "True") {
                    arg(line: "$pathToInstaller -d $installationFolder")
                }
                println("##teamcity[blockClosed name='unzip output']")
                break
            default:
                throw new IllegalArgumentException("Wrong extension: $extension")
        }

        switch (context.os) {
            case OS.WIN:
                return installationFolder
            case OS.LINUX:
                return getBuildFolder(installationFolder, 1)
            case OS.MAC:
                return getBuildFolder(installationFolder, 2)
            default:
                throw new IllegalArgumentException("Wrong os: $context.os")
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

    Build installBuild(Path installationFolder = null, String prefix = "") {
        if (installationFolder == null) {
            installationFolder = context.tempDirectory.resolve("$prefix-${installerName.replace('.', '-')}")
        }
        download()
        Path buildFolder = install(installationFolder)
        return new Build(context, buildFolder)
    }

    void delete() {
        context.ant.delete(file: getInstallerPath().getAbsoluteFile())
    }

}


class Build {
    private Context context
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
     */
    Build(Context context, Path buildFolder) {
        this.context = context
        this.buildFolder = buildFolder
    }

    String calcChecksum() {
        Path checksumFolder = context.tempDirectory.resolve("checksums")

        println('')
        context.ant.echo("Calculating checksum")
        context.ant.mkdir(dir: checksumFolder)
        context.ant.checksum(todir: checksumFolder, totalproperty: 'sum') {
            fileset(dir: buildFolder) {
                if (context.os == OS.WIN) {
                    exclude(name: "**\\Uninstall.exe")
                    exclude(name: "**\\classes.jsa")
                } else if (context.os == OS.MAC) {
                    exclude(name: "**\\*.dylib")
                }
            }
        }
        context.ant.delete(dir: checksumFolder)
        context.ant.echo("Checksum is $context.ant.project.properties.sum")
        return context.ant.project.properties.sum
    }

    void patch(File patch) {
        Path log4jJar = Paths.get(context.tempDirectory.toString(), 'log4j.jar')
        buildFolder.resolve('lib').toFile().eachFileRecurse(FileType.FILES) { file ->
            if (file.name == 'log4j.jar') {
                Files.copy(file.toPath(), log4jJar, REPLACE_EXISTING)
            }
        }
        if (log4jJar == null) {
            throw new RuntimeException("log4j.jar wasn't found")
        }

        println('')
        context.ant.echo("Applying patch $patch.name")
        Path out = context.out.resolve(patch.name)
        context.ant.mkdir(dir: out)

        context.ant.java(classpath: context.ant.path { pathelement(path: patch); pathelement(path: log4jJar) },
                         classname: "com.intellij.updater.Runner",
                         fork: "true",
                         maxmemory: "800m",
                         timeout: context.timeout * 3000,
                         resultproperty: 'patchResult') {
            jvmarg(value: "-Didea.updater.log=$out")
            arg(line: "install '$buildFolder'")
        }
        switch (context.ant.project.properties.patchResult) {
            case '42':
                context.ant.echo("Message 'Java Result: 42' is OK, because this error is thrown from GUI " +
                        "and it means that IDE restart is needed")
                break
            case '-1':
                throw new KnownException("Patch process failed with -1 result")
            default:
                context.ant.echo("Java result $context.ant.project.properties.patchResult is unexpected here, please check")
        }
    }

    void delete() {
        context.ant.delete(dir: buildFolder.toString())
    }

    boolean isSignatureValid() {
        switch (context.os) {
            case OS.MAC:
                Path folder = buildFolder.resolve('../').toAbsolutePath()
                context.ant.exec(executable: "codesign", failonerror: "False", outputproperty: "checkOutput", resultproperty: 'checkResult') {
                    arg(line: "-vv '$folder'")
                }
                context.ant.echo(context.ant.project.properties.checkOutput)
                return (context.ant.project.properties.checkResult == '0')
//            case OS.WIN:
//                TODO
            default:
                context.ant.echo("Signature can't be verified for this OS")
                return true
        }
    }
}


abstract class PatchTestClass {
    protected Context context

    PatchTestClass(Context context) {
        this.context = context
    }

    protected setUp() { null }

    abstract protected runTest()

    protected tearDown() { null }

    protected run() {
        setUp()
        runTest()
        tearDown()
    }
}


class PatchTestSuite extends PatchTestClass {
    private ArrayList<File> patches

    PatchTestSuite(Context context) {
        super(context)
    }

    protected setUp() {
        context.ant.delete(dir: context.patchesDir)
        context.build.downloadArtifacts("*$context.platform*", new File(context.patchesDir))

        patches = findFiles('.jar', new File(context.patchesDir))
        println("##teamcity[enteredTheMatrix]")
        int testCount = patches.size() * context.extensions.size()
        println("##teamcity[testCount count='$testCount']")
        println("##teamcity[testSuiteStarted name='Patch Update Autotest']")
    }

    protected runTest() {
        patches.each { File patch ->
            context.extensions.each { String extension ->
                new PatchTestCase(context, patch, extension).run()
            }
        }
    }

    protected tearDown() {
        println("##teamcity[testSuiteFinished name='Patch Update Autotest']")
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

}


class PatchTestCase extends PatchTestClass {
    private File patch
    private String extension
    private String testName
    private Installer prevInstaller
    private Installer currInstaller

    PatchTestCase(Context context, File patch, String extension) {
        super(context)
        this.patch = patch
        this.extension = extension
    }

    protected setUp() {
        String patchName = patch.getName()
        List<String> partsOfPatchName = patchName.split('-')

        String edition = partsOfPatchName.get(0)
        edition = edition in ['IC', 'IU', 'PC', 'PY', 'PE'] ? edition : ''
        edition = (edition == 'PE') ? 'EDU' : edition

        String previousInstallerName = partsOfPatchName.get(1)
        String currentInstallerName = partsOfPatchName.get(2)
        boolean withBundledJdk = (!patchName.contains('no-jdk'))

        String product = context.product.substring(0, 1).toUpperCase() + context.product.substring(1)
        testName = "${product} ${edition}${withBundledJdk ? '' : ' (no-jdk)'}" +
                   "${(edition || !withBundledJdk) ? ' edition' : ''} test, $extension installers"
        println("##teamcity[testStarted name='$testName']")

        prevInstaller = new Installer(context, previousInstallerName, edition, extension, withBundledJdk)
        currInstaller = new Installer(context, currentInstallerName, edition, extension, withBundledJdk)
        context.ant.mkdir(dir: context.tempDirectory.toString())
    }

    protected runTest() {
        try {
            Build prevBuild = prevInstaller.installBuild(null, "previous")
            prevBuild.calcChecksum()
            if (context.os == OS.MAC && !prevBuild.isSignatureValid()) println('Signature verification failed')

            prevBuild.patch(patch)
            String prevChecksum = prevBuild.calcChecksum()
            if (context.os == OS.MAC && !prevBuild.isSignatureValid()) throw new KnownException('Signature verification failed')
            println('')

            Build currBuild = currInstaller.installBuild(null, "current")
            String currChecksum = currBuild.calcChecksum()
            if (context.os == OS.MAC && !currBuild.isSignatureValid()) throw new KnownException('Signature verification failed')

            if (prevChecksum != currChecksum) throw new KnownException("Checksums are different: $prevChecksum and $currChecksum")
            println("\nBuild checksums of $extension installers are equal: $prevChecksum and $currChecksum\n")
        }
        catch (WrongConfigurationException e) {
            println("##teamcity[testIgnored name='$testName'] message='$e.message']")
        }
        catch (KnownException e) {
            println("##teamcity[testFailed name='$testName'] message='$e.message']")
        }
        catch (e) {
            e.printStackTrace()
            println("##teamcity[testFailed name='$testName'] message='$e.message']")
        }
    }

    protected tearDown() {
        context.ant.delete(dir: context.tempDirectory.toString())
        println("##teamcity[testFinished name='$testName']")
    }

}


Map<String, String> map = evaluate(Arrays.toString(args)) as Map<String, String>
Context context = new Context(map)
new PatchTestSuite(context).run()
