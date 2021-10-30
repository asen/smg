#!/usr/bin/env ruby

require 'json'
require 'uri'
require 'net/http'


SERVICE_KEY = ARGV.shift || ENV["SMG_PAGERDUTY_SERVICE_KEY"]

raise "need a service key" if SERVICE_KEY.nil?

SMG_SEVERITY=ENV["SMG_ALERT_SEVERITY"] #-$2  # one of  RECOVERY, ACKNOWLEDGEMENT, ANOMALY, WARNING, FAILED, CRITICAL, SMGERR, THROTTLED, UNTHROTTLED
SMG_ALERT_KEY=ENV["SMG_ALERT_KEY"] #$3
SMG_SUBJ=ENV["SMG_ALERT_SUBJECT"] #$4
SMG_BODY=ENV["SMG_ALERT_BODY"] || "" #$5

# https://v2.developer.pagerduty.com/docs/events-api

event_type = case SMG_SEVERITY
    when "RECOVERY", "UNTHROTTLED"
      "resolve"
    when "ACKNOWLEDGEMENT"
      "acknowledge"
    else
      "trigger"
  end

link_line = SMG_BODY.split("\n").find {|ln| /link:\s+/ =~ ln }
client_url = link_line.nil? ? "UNKNOWN" : link_line.gsub(/.*link:\s+/, "").gsub(/\s+.*$/,"")


hash = {
  :service_key => SERVICE_KEY,
  :event_type => event_type,
  :incident_key => SMG_ALERT_KEY,
  :description => SMG_SUBJ,
  :details => {"severity" => SMG_SEVERITY, "body" => SMG_BODY},
  :client => "SMG",
  :client_url => client_url,
}

# puts JSON.dump(hash)

proxy_host = nil
proxy_port = nil
proxy_str= ENV["https_proxy"] || ENV["HTTPS_PROXY"]
if proxy_str
  proxy_str=proxy_str.gsub(/https?:\/\//,"")
  if proxy_str != ""
    proxy_host, proxy_port = proxy_str.split(":",2)
    proxy_port = proxy_port.nil? ? 80 : proxy_port.to_i
  end
end

uri = URI.parse('https://events.pagerduty.com/generic/2010-04-15/create_event.json')
req = Net::HTTP::Post.new(uri.request_uri, 'Content-Type' => 'application/json')
req.body = JSON.dump(hash)
# p uri
http = Net::HTTP.new(uri.host, uri.port, proxy_host, proxy_port)
http.use_ssl = true
res = http.request(req)
puts res.body
begin
  resjs = JSON.parse(res.body)
  if resjs["status"] == "success"
    exit 0
  else
    exit 1
  end
rescue Exception => e
  exit 2
end
