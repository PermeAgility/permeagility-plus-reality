function unhex(str)
    str = string.gsub (str, "(%x%x) ?",
        function(h) return string.char(tonumber(h,16)) end)
    return str
end

s=net.createServer(net.UDP)
s:on("receive",function(s,c) 
print("DNS")
transaction_id=string.sub(c,1,2)
flags=string.sub(c,3,4)
questions=string.sub(c,5,6)

query = ""
raw_query = ""
j=13
while true do
    byte = string.sub(c,j,j)
    j=j+1
    raw_query = raw_query .. byte
    if byte:byte(1)==0x00 then --NULL marks end of the string.
        break
    end
    for i=1,byte:byte(1) do
        byte = string.sub(c,j,j)
        j=j+1
        raw_query = raw_query .. byte
        query = query .. byte
    end
    query = query .. '.'
end
query=query:sub(1,query:len()-1) --strip the trailing dot.
q_type = string.sub(c,j,j+1)
j=j+2
if q_type == unhex("00 01") then 
--print("Got a type A query "..query)
class = string.sub(c,j,j+1)

--ip = "192.168.4.1"
ip=unhex("C0 A8 04 01")
answers = unhex("00 01")
flags = unhex("81 80")

resp=transaction_id..flags..questions..answers..unhex("00 00")..unhex("00 00")..raw_query..q_type..class
resp=resp..unhex("c0 0c")..q_type..class..unhex("00 00 00 da")..unhex("00 04")..ip
s:send(resp)
end
end) 
s:on("sent",function(s) 
--s:close() 
end)
s:listen(53)
print("listening, free:", node.heap())