--node.key("long", function() 
--    print('clearing memory')
--    file.remove("connected") 
--    node.restart()
--end)
if file.open("connected","r") == nil then
    wifi.setmode(wifi.SOFTAP)
    cfg={}
    cfg.ssid="permeagility" .. node.chipid()
    cfg.pwd="permeagility"
    wifi.ap.config(cfg)
    dofile("captdns.lua")
    dofile("station.lua")
else
    dofile("client.lua")
end
