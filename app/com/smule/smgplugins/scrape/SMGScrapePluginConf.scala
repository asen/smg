package com.smule.smgplugins.scrape

case class SMGScrapePluginConf(targets: Seq[SMGScrapeTargetConf]) {

//scrape:
//  autoconf:
//  - include: /etc/smg/scrape-targets.yml
//  - name: test
//    conf_output: /etc/smg/conf.d/test.yml
//    command: curl http://skube-int.oak.smle.co:31025/metrics
//    timeout: 30
//    filter:
//      rxx: "^prometheus"

}
