(defproject edu.umass/surveyman "1.5"
  :description "SurveyMan is a programming language and runtime system for designing, debugging, and deploying surveys on the web at scale."
  :url "http://surveyman.org"
  :repositories [["java.net" "http://download.java.net/maven/2"]]
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [com.github.fge/json-schema-validator "2.0.1"]
                 [incanter "1.5.4"]
                 [clj-webdriver "0.6.0"]
                 [org.seleniumhq.selenium/selenium-java "2.41.0"]
                 [org.clojure/math.numeric-tower "0.0.4"]
                 [com.googlecode.htmlcompressor/htmlcompressor "1.4"]
                 [com.google.javascript/closure-compiler "v20130823"]
                 [com.yahoo.platform.yui/yuicompressor "2.4.6"]
                 [net.sourceforge.htmlunit/htmlunit "2.13"]
                 [xml-apis/xml-apis "1.4.01"]
                 [net.java.dev.activeobjects/activeobjects "0.8.2"]
                 [org.apache.axis/axis "1.4"]
                 [org.apache.axis/axis-jaxrpc "1.4"]
                 [org.apache.axis/axis-saaj "1.4"]
                 [wsdl4j/wsdl4j "1.5.1"]
                 [commons-codec/commons-codec "1.3"]
                 [commons-beanutils/commons-beanutils "1.7.0"]
                 [commons-collections/commons-collections "3.2"]
                 [commons-dbcp/commons-dbcp "1.2.2"]
                 [commons-digester/commons-digester "1.8"]
                 [commons-discovery/commons-discovery "0.5"]
                 [commons-httpclient/commons-httpclient "3.1"]
                 [commons-io/commons-io "2.4"]
                 [commons-lang/commons-lang "2.3"]
                 [commons-pool/commons-pool "1.3"]
                 [org.apache.derby/derby "10.9.1.0"]
                 [log4j/log4j "1.2.12"]
                 [dom4j/dom4j "1.6.1"] 
                 [com.google.code.gson/gson "2.2.4"]
                 [jaxen/jaxen "1.1"]
                 [ca.juliusdavies/not-yet-commons-ssl "0.3.11"]
                 [velocity/velocity "1.5"]
                 [net.sf.supercsv/super-csv "2.1.0"]
                 [jaxme/jaxme2 "0.5.1"]
                 [jaxme/jaxme-api "0.3.1"]
                 [org.apache.ws.jaxme/jaxmexs "0.5.2"]
                 [com.googlecode.json-simple/json-simple "1.1.1"]
                 [com.amazonaws/java-aws-mturk "1.6.2"]
                 [com.amazonaws/aws-mturk-dataschema "1.6.2"]
                 [com.amazonaws/aws-mturk-wsdl "1.6.2"]
                 [org.hsqldb/hsqldb "2.0.0"]
                 [org.eclipse.jetty/jetty-server "7.6.8.v20121106"]
                 [org.apache.commons/commons-math3 "3.0"]
                 ]
  :source-paths ["src/main/clojure"]
  :java-source-paths ["src/main/java"]
  :test-paths ["src/test/clojure"]
  :resource-paths ["src/main/resources"]
  :aot [qc.analyses qc.report]
  :main qc.report
  )
