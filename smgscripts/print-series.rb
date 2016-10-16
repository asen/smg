#!/usr/bin/env ruby

# to generate a series file
# ruby -e 'puts (50.times.map{|i| rand(10) } + 15.times.map {|i| rand(100) }).join(" ")'

fn = ARGV.shift
statefn = "/tmp/print_series_#{File.basename(fn)}.state"

cur_ix = 0
if File.exists?(statefn)
  cur_ix = File.read(statefn).strip.to_i
end

nums = File.read(fn).split(/\s+/)

if (cur_ix >= nums.size)
  cur_ix = 0
end

File.open(statefn, "w") { |io| io.puts cur_ix + 1 }

puts nums[cur_ix]
puts cur_ix
