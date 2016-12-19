import groovy.io.FileType
import java.nio.file.Path
import java.nio.file.Paths

platformMatrix = [win: "exe",
                  unix: "tar.gz",
                  mac: "sit"]

def map = evaluate(Arrays.toString(args))
println(sprintf("Args: $map"))

product = map.product
os = map.platform
if (platformMatrix.containsKey(os)){
    extension = platformMatrix.get(os)
} else {
    throw new RuntimeException(sprintf("Wrong os: $map.platform"))
}
buildType = map.buildType
timeout = map.timeout


class Build{
    def binding
    def checksum
    Path folder
    def edition
    def id
    def installerName
    def name
    def link
    def version
    def log4j


    Build(edition, version, binding, jdk=true){
        this.edition = edition
        this.version = version
        this.binding = binding
        this.installerName = sprintf('%s%s-%s%s.%s', [binding.product,
                                                      edition,
                                                      version,
                                                      (jdk) ? '' : '-no-jdk',
                                                      binding.extension])
        this.getId()
        this.getLink()
    }

    void calcChecksum(){
        def ant = new AntBuilder()
        ant.mkdir(dir: "checksums")
        ant.checksum(todir: "checksums", totalproperty: 'sum'){
            fileset(dir: this.folder){
                if (binding.os=='win') {
                    exclude(name: "**\\Uninstall.exe")
                    exclude(name: "**\\classes.jsa")
                }
            }
        }
        ant.delete(dir: "checksums")
        this.checksum = ant.project.properties.sum
        ant.echo("Checksum is $checksum")
    }

    def getChecksum(){
        if (!checksum){
            calcChecksum()
        }
        return checksum
    }

    def getId(){
        if (!this.id){
            def url = "http://buildserver.labs.intellij.net/guestAuth/app/rest/builds/buildType:$binding.buildType,number:$version/id"
            this.id = new URL(url).getText()
        }
        return this.id
    }

    def getLog4j(){
        this.folder.toFile().eachFileRecurse(FileType.FILES) { file ->
            if (file.name.contains('log4j.jar')) {
                this.log4j = file
            }
        }
        return this.log4j
    }

    void downloadBuild(){
        def ant = new AntBuilder()
        ant.get(dest: this.installerName) {
            url(url: "http://buildserver.labs.intellij.net/guestAuth/repository/download/$binding.buildType/$id:id/$installerName")
        }
    }

    void install(toFolder){
        deleteFolder(toFolder)
        def ant = new AntBuilder()
        ant.mkdir(dir: toFolder)
        this.folder = Paths.get(toFolder.toString())

        switch (binding.os) {
            case 'win':
                ant.exec(executable: "cmd", failonerror: "True") {
                    arg(line: "/k $installerName /S /D=$folder.absolutePath && ping 127.0.0.1 -n $binding.timeout > nul")
                }
                break
            case 'unix':
                ant.gunzip(src: this.installerName)
                ant.delete(file: this.installerName)
                ant.untar(src: this.installerName[0..-4], dest: this.folder)

                redefineFolder()
                break
            case 'mac':
                println("##teamcity[blockOpened name='unzip output']")
                ant.exec(executable: "unzip", failonerror: "True"){
                    arg(line: "$installerName -d $folder")
                }
                println("##teamcity[blockClosed name='unzip output']")

                redefineFolder(2)
                break
            default:
                throw new RuntimeException(sprintf("Wrong os: $binding.os"))
        }

        calcChecksum()
    }

    void redefineFolder(depth=1){
        assert depth > 0
        for (i in 1..depth) {
            this.folder.eachDir { directory ->
                this.folder = Paths.get(directory.toString())
            }
        }
    }

    void deleteFolder(folder=this.folder){
        def ant = new AntBuilder()
        ant.delete(dir: folder)
    }

    void patch(patch){
        def ant = new AntBuilder()
        def classpath = ant.path {
            pathelement(path: patch)
            pathelement(path: this.getLog4j())
        }
        ant.java(classpath: "${classpath}",
                 classname: "com.intellij.updater.Runner",
                 fork: "true",
                 maxmemory: "800m",
                 timeout: binding.timeout.toInteger() * 2000){
            arg(line: "install '$folder'")
        }
        calcChecksum()
    }
}


static def findFiles(mask, directory='.') {
    def list = []
    def dir = new File(directory)
    dir.eachFileRecurse (FileType.FILES) { file ->
        if (file.name.contains(mask)) {
            println(file)
            list << file
        }
    }
    return list
}

def main(dir='patches'){
    patches = findFiles(mask='.jar', directory=dir)
    println("##teamcity[enteredTheMatrix]")
    println("##teamcity[testCount count='$patches.size']")
    println("##teamcity[testSuiteStarted name='Patch Update Autotest']")

    patches.each { patch ->
        patchName = patch.getName()
        splitz = patchName.split('-')

        jdk = (!patchName.contains('no-jdk'))
        testName = sprintf("%s%s edition test, patch name: %s", [splitz[0],
                                                                  (jdk) ? '' : ' (no-jdk)',
                                                                  patchName])
        println(sprintf("##teamcity[testStarted name='%s']", testName))

        prev = new Build(splitz[0], splitz[1], binding, jdk)
        curr = new Build(splitz[0], splitz[2], binding, jdk)

        prev.downloadBuild()
        prev.install('prev')
        prev.patch(patch)

        curr.downloadBuild()
        curr.install('curr')

        if (prev.checksum != curr.checksum){
            println(sprintf("##teamcity[testFailed name='%s'] message='Checksums are different: %s and %s']",
                    [testName, prev.checksum, curr.checksum]))
        }

        println(sprintf("##teamcity[testFinished name='%s']", testName))
        prev.deleteFolder()
        curr.deleteFolder()
    }
    println("##teamcity[testSuiteFinished name='Patch Update Autotest']")
}

main()