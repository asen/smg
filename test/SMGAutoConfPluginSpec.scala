import com.smule.smg.core.{CommandResultListString, ParentCommandData, SMGFileUtil, SMGLogger}
import com.smule.smgplugins.autoconf.SMGTemplateProcessor
import com.smule.smgplugins.cc.csv.SMGCsvCommands
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import java.util

@RunWith(classOf[JUnitRunner])
class SMGAutoConfPluginSpec extends Specification{

  private val log = SMGLogger

  "SMGTemplateProcessor" should {
    "work with haproxy" in {
      val p = new SMGTemplateProcessor(log)
      val out = p.processTemplate("smgconf/ac-templates/haproxy.yml.ssp", Map(
        "node_name" -> "test1",
        "command" -> "curl -sS -f 'http://test1/stats;csv'",
        "data" -> Seq(
"# pxname,svname,qcur,qmax,scur,smax,slim,stot,bin,bout,dreq,dresp,ereq,econ,eresp,wretr,wredis,status,weight,act,bck,chkfail,chkdown,lastchg,downtime,qlimit,pid,iid,sid,throttle,lbtot,tracked,type,rate,rate_lim,rate_max,check_status,check_code,check_duration,hrsp_1xx,hrsp_2xx,hrsp_3xx,hrsp_4xx,hrsp_5xx,hrsp_other,hanafail,req_rate,req_rate_max,req_tot,cli_abrt,srv_abrt,comp_in,comp_out,comp_byp,comp_rsp,lastsess,last_chk,last_agt,qtime,ctime,rtime,ttime,",
"http_proxy,FRONTEND,,,27,680,50000,50992976,53884212388,127144709218,1421,0,3932,,,,,OPEN,,,,,,,,,1,2,0,,,,0,879,0,1684,,,,0,50808928,105907,74507,3613,15,,880,1684,50992976,,,0,0,0,0,,,,,,,,",
"nginx_buffer_farm,BACKEND,0,0,0,8,5000,444279,380093310,1847431489,0,0,,0,0,0,0,UP,1,1,0,,0,974060,0,,1,4,0,,444279,,1,5,,32,,,,0,410593,170,33513,3,0,,,,,31,0,0,0,0,0,0,,,0,0,5,5,",
"nginx_gzip_buffer_farm,nginx_6080,0,0,5,125,20000,8588023,9369066870,21334887356,,0,,0,0,0,0,no check,1,1,0,,,,,,1,5,1,,8588027,,2,142,,351,,,,0,8561594,23830,1572,370,0,0,,,,659,0,,,,,0,,,0,0,30,30",
"nginx_gzip_buffer_farm,nginx_6081,0,0,5,126,20000,8595505,9440821308,21340361699,,0,,0,0,0,0,no check,1,1,0,,,,,,1,5,2,,8595509,,2,125,,346,,,,0,8569122,23899,1511,339,0,0,,,,636,0,,,,,0,,,0,0,36,36,"
        )
      )).get
      println("===================")
      println(out)
      val out2 = p.processTemplate("smgconf/ac-templates/haproxy.yml.ssp", Map(
        "node_name" -> "test1",
        "command" -> "curl -sS -f 'http://test1/stats;csv'",
        "data" -> Seq(
          "# pxname,svname,qcur,qmax,scur,smax,slim,stot,bin,bout,dreq,dresp,ereq,econ,eresp,wretr,wredis,status,weight,act,bck,chkfail,chkdown,lastchg,downtime,qlimit,pid,iid,sid,throttle,lbtot,tracked,type,rate,rate_lim,rate_max,check_status,check_code,check_duration,hrsp_1xx,hrsp_2xx,hrsp_3xx,hrsp_4xx,hrsp_5xx,hrsp_other,hanafail,req_rate,req_rate_max,req_tot,cli_abrt,srv_abrt,comp_in,comp_out,comp_byp,comp_rsp,lastsess,last_chk,last_agt,qtime,ctime,rtime,ttime,"
        )
      )).get
      println("===================")
      println(out2)
      1.equals(1)
    }

    "work with redis" in {
      val p = new SMGTemplateProcessor(log)
      val out = p.processTemplate("smgconf/ac-templates/redis.yml.ssp", Map(
        "node_name" -> "localhost",
        "node_host" -> "localhost"
      )).get
      println("===================")
      println(out)
      val portRange = new util.ArrayList[String]()
      portRange.add("6379")
      portRange.add("6380")
      val out2 = p.processTemplate("smgconf/ac-templates/redis.yml.ssp", Map(
        "node_name" -> "localhost",
        "node_host" -> "someother_host",
        "port_range" -> portRange
      )).get
      println("===================")
      println(out2)
      1.equals(1)
    }

    "work with linux-snmp-static" in {
      val p = new SMGTemplateProcessor(log)
      val out = p.processTemplate("smgconf/ac-templates/linux-snmp-static.yml.ssp", Map(
        "node_name" -> "localhost",
        "node_host" -> "localhost"
      )).get
      println("===================")
      println(out)
      val dd = new util.ArrayList[java.util.Map[String,Object]]()
      val d1 = new java.util.HashMap[String,Object]()
      d1.put("mount", "/")
      d1.put("oid", "34")
      dd.add(d1)
      val out2 = p.processTemplate("smgconf/ac-templates/linux-snmp-static.yml.ssp", Map(
        "node_name" -> "localhost",
        "node_host" -> "someother_host",
        "disk_drives" -> dd
      )).get
      println("===================")
      println(out2)
      1.equals(1)
    }

    "work with kafka-consumers" in {
      val data = SMGFileUtil.getFileLines("test-data/kafka-groups-ka11.txt")
      val csv = new SMGCsvCommands(log)
      val parsed = csv.csvCommand("csv", "parserx", 30,
        Some(ParentCommandData(CommandResultListString(data.toList, None), None)))
      println(parsed.data.asInstanceOf[csv.CSVParsedData].dump())

      val p = new SMGTemplateProcessor(log)
      val out = p.processTemplate("smgconf/ac-templates/kafka-consumers.yml.ssp", Map(
        "command" -> "cat test-data/kafka-groups-ka11.txt",
        "data" -> data
      )).get
      println("===================")
      println(out)

      1.equals(1)
    }

    "work with openmetrics" in {
//      val data = SMGFileUtil.getFileLines("test-data/smg-metrics.txt")
      val data = SMGFileUtil.getFileLines("test-data/metrics.txt")
      val p = new SMGTemplateProcessor(log)
      val out = p.processTemplate("smgconf/ac-templates/openmetrics.yml.ssp", Map(
        "command" -> "cat 'test-data/smg-metrics.txt'",
        "node_name" -> "localhost",
        "node_host" -> "localhost",
        "data" -> data
      )).get
      println("===================")
      println(out)
      1.equals(1)
    }

    "work with cadvisor" in {
        val data = SMGFileUtil.getFileLines("test-data/metrics-cadvisor.txt")
        val p = new SMGTemplateProcessor(log)
        val out = p.processTemplate("smgconf/ac-templates/cadvisor-k8s.yml.ssp", Map(
          "node_name" -> "localhost",
          "node_host" -> "localhost",
          "command" -> "cat 'test-data/metrics-cadvisor.txt'",
          "need_parse" -> Boolean.box(true),
          "data" -> data
        )).get
        println("===================")
        println(out)
        1.equals(1)
      }
  }
}

