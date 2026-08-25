node('linux') {
  stage ('Poll') {
    checkout([
      $class: 'GitSCM', branches: [[name: '*/main']], extensions: [],
      userRemoteConfigs: [[url: 'https://github.com/zopencommunity/pyarrowport.git']]])
  }
  stage('Build') {
    build job: 'Port-Pipeline', parameters: [
      string(name: 'PORT_GITHUB_REPO', value: 'https://github.com/zopencommunity/pyarrowport.git'),
      string(name: 'PORT_DESCRIPTION', value: 'pyarrow, Python bindings for Apache Arrow'),
      string(name: 'BUILD_LINE', value: 'STABLE')
    ]
  }
}
