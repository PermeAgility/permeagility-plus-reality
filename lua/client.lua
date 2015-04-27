print("starting PermeAgility server")
print(wifi.sta.getip())
srv=net.createServer(net.TCP) srv:listen(80,function(conn)
conn:on("receive",function(conn,payload)
print(payload)
if string.find(payload,"favicon.ico") == nil then
end
conn:send("HTTP/1.1 200 OK\n\n<html><body>" .. "Connected!" .. "</body></html>")
end)
conn:on("sent",function(conn) conn:close() end)
end)
