#!/usr/bin/env ruby

RRDTOOL = "rrdtool"
inf = ARGV.shift

if inf.nil?
    puts "need input rrd file"
    exit 1
end

def mysys(cmd)
 if not system(cmd)
   puts "Command failed: #{cmd}"
   exit -1
 end
end

mysys("rm -f /tmp/rrd5to1.rrd")
mysys("rm -f /tmp/rrd5to1.xml")
mysys("#{RRDTOOL} dump #{inf} > /tmp/rrd5to1.xml")
# edit xml here
inlines = File.read("/tmp/rrd5to1.xml").split("\n")
File.open("/tmp/rrd5to1.xml", "w") do |io|
  inlines.each do |ln|
    mh = /<minimal_heartbeat>600<\/minimal_heartbeat>/.match(ln)
    ms = /<step>300<\/step>/.match(ln)
    m = /<pdp_per_row>(\d+)<\/pdp_per_row>/.match(ln)
    if m.nil? && ms.nil? && mh.nil?
      io.puts ln
    elsif !mh.nil?
      io.puts "<minimal_heartbeat>120</minimal_heartbeat>"
    elsif !ms.nil?
      io.puts "<step>60</step>"
    else
      io.puts "<pdp_per_row>#{m[1].to_i * 5}</pdp_per_row>"
    end
  end
end
mysys("#{RRDTOOL} restore /tmp/rrd5to1.xml /tmp/rrd5to1.rrd")
mysys("#{RRDTOOL} tune /tmp/rrd5to1.rrd RRA:AVERAGE:0.5:1:5760 RRA:MAX:0.5:1:5760")
mysys("mv #{inf} #{inf}.bck")
mysys("mv /tmp/rrd5to1.rrd #{inf}")
