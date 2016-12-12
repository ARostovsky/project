import groovy.io.FileType
import java.nio.file.Path
import java.nio.file.Paths


product = "pycharm"
platform = %platform.ext% // "exe"
buildType = %buildType% //"ijplatform_master_PyCharm_InstallersForEapRelease"
currDir = %teamcity.build.checkoutDir% // Paths.get('folder').toAbsolutePath()
timeout = 30


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


    Build(edition, version, binding){
        this.edition = edition
        this.version = version
        this.binding = binding
        this.installerName = sprintf('%1$s%2$s-%3$s.%4$s', [binding.product, edition, version, binding.platform])
        this.getId()
        this.getLink()
    }

    void calcChecksum(){
        def ant = new AntBuilder()
        ant.mkdir(dir: "checksums")
        ant.checksum(todir: "checksums", totalproperty: 'sum'){
            fileset(dir: this.folder){
                exclude(name: "**\\Uninstall.exe")
                exclude(name: "**\\classes.jsa")
            }
        }
        ant.delete(dir: "checksums")
        this.checksum = ant.project.properties.sum
        ant.echo(this.checksum)
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

    void downloadBuild(){
        def ant = new AntBuilder()
        ant.get(dest: this.installerName) {
            url(url: "http://buildserver.labs.intellij.net/guestAuth/repository/download/$binding.buildType/$id:id/$installerName")
        }
    }

    void install(toFolder){
        delete(toFolder)
        def ant = new AntBuilder()
        ant.mkdir(dir: toFolder)
        this.folder = Paths.get(toFolder)
        ant.exec(executable: "cmd", failonerror: "True") {
            arg(line: "/k $installerName /S /D=$folder.absolutePath && ping 127.0.0.1 -n $binding.timeout > nul")
        }
        calcChecksum()
    }

    void delete(toFolder=this.folder){
        def ant = new AntBuilder()
        ant.delete(dir: toFolder)
    }

    void patch(patch){
        def ant = new AntBuilder()
        def classpath = ant.path {
            pathelement(path: patch)
            pathelement(path: sprintf("$folder\\lib\\log4j.jar"))
        }
        ant.java(classpath: "${classpath}",
                 classname: "com.intellij.updater.Runner",
                 fork: "true",
                 maxmemory: "800m",
                 timeout: binding.timeout * 1000){
            arg(line: "install '$folder'")
        }
        calcChecksum()
    }
}
//abc

static def findFiles(mask, directory='.') {
    def list = []
    def dir = new File(directory)
    dir.eachFileRecurse (FileType.FILES) { file ->
        if (file.name.contains(mask)) {
            list << file
        }
    }
    return list
}

def main(dir='.'){
    patches = findFiles(mask='.jar', directory=dir)
    println("##teamcity[enteredTheMatrix]")
    println("##teamcity[testCount count='$patches.size']")
    println("##teamcity[testSuiteStarted name='Patch Update Autotest']")

    patches.each { patch ->
        splitz = patch.getName().split('-')
        println(sprintf("##teamcity[testStarted name='%s edition test']", splitz[0]))
        prev = new Build(splitz[0], splitz[1], binding)
        curr = new Build(splitz[0], splitz[2], binding)

        prev.downloadBuild()
        prev.install('prev')
        prev.patch(patch)

        curr.downloadBuild()
        curr.install('curr')

        if (prev.checksum != curr.checksum){
            println(sprintf("##teamcity[testFailed name='%s edition test'] message='Checksums are different: %s and %s']",
                    [splitz[0], prev.checksum, curr.checksum]))
        }

        println(sprintf("##teamcity[testFinished name='%s edition test']", splitz[0]))
        prev.delete()
        curr.delete()
    }
    println("##teamcity[testSuiteFinished name='Patch Update Autotest']")
}

main()