#!/usr/bin/env ruby

require 'json'

city = ARGV.shift
stats = ARGV

# s = File.readlines("/tmp/openweathermap-sf.out")
s = $stdin.readlines

d = JSON.parse(s.join("\n"))

cd = d["list"].find {|h| h["name"] == city}

stats.each do |stat|
  arr = stat.split(":")
  if arr.size == 1
    puts cd[arr[0]].to_f
  else
    puts cd[arr[0]][arr[1]].to_f
  end
end
