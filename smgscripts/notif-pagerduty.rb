#!/usr/bin/env ruby

require 'json'
require 'uri'
require 'net/http'


SERVICE_KEY = ARGV.shift

raise "need a service key" if SERVICE_KEY.nil?

SMG_SEVERITY=ENV["SMG_ALERT_SEVERITY"] #-$2  # one of  RECOVERY, ACKNOWLEDGEMENT, ANOMALY, WARNING, UNKNOWN, CRITICAL, SMGERR, THROTTLED, UNTHROTTLED
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

link_line = SMG_BODY.split("\n").find {|ln| /LINK:\s+/ =~ ln }
client_url = link_line.nil? ? "UNKNOWN" : link_line.gsub(/.*LINK:\s+/, "").gsub(/\s+.*$/,"")


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

uri = URI.parse('https://events.pagerduty.com/generic/2010-04-15/create_event.json')
req = Net::HTTP::Post.new(uri.request_uri, 'Content-Type' => 'application/json')
req.body = JSON.dump(hash)
# p uri
http = Net::HTTP.new(uri.host, uri.port)
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
