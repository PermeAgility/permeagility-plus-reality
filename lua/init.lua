--node.key("long", function() 
--    print('clearing memory')
--    file.remove("connected") 
--    node.restart()
--end)
if file.open("connected","r") == nil then
    wifi.setmode(wifi.SOFTAP)
    cfg={}
    cfg.ssid="permeagility"..node.chipid()
    cfg.pwd="permeagility"
    wifi.ap.config(cfg)
    --dofile("captdns.lua")
    dofile("station.lua")
else
    wifi.setmode(wifi.STATION)
    wifi.sta.config("2B1A38","247586762")

    -- delay 5 seconds before starting so that we can 
    -- cancel it with =tmr.stop(0)
    tmr.alarm(0,5000,0,function() dofile('client.lua') end)
end
