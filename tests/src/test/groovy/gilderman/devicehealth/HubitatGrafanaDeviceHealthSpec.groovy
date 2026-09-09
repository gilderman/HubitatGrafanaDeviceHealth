package gilderman.devicehealth

import groovy.json.JsonSlurper
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import spock.lang.Specification

/**
 * Tests for this repo's app. Gradle + hubitat_ci live in hubitat-deploy;
 * run with: hubitat test --cwd this-repo
 */
class HubitatGrafanaDeviceHealthSpec extends Specification {
    static final File PROJECT_DIR = new File(System.getProperty('hubitat.projectDir', '.')).canonicalFile
    static final File APP = new File(PROJECT_DIR, 'apps/HubitatGrafanaDeviceHealth.groovy')
    static final File MANIFEST = new File(PROJECT_DIR, 'packageManifest.json')

    def "app source exists"() {
        expect:
            APP.isFile()
    }

    def "app compiles in HubitatAppSandbox"() {
        when:
            new HubitatAppSandbox(APP).compile()
        then:
            noExceptionThrown()
    }

    def "appVersion matches packageManifest"() {
        given:
            def groovy = APP.getText('UTF-8')
            def matcher = groovy =~ /def appVersion\(\)\s*\{\s*"([^"]+)"\s*\}/
            def manifest = new JsonSlurper().parse(MANIFEST)
        expect:
            matcher.find()
            matcher.group(1) == manifest.version
    }
}
