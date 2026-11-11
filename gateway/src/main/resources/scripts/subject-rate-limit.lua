local windowMillis = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
local requestId = ARGV[3]
local now = redis.call('TIME')
local nowMillis = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)

redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', nowMillis - windowMillis)
if redis.call('ZCARD', KEYS[1]) >= limit then
    return 0
end

redis.call('ZADD', KEYS[1], nowMillis, nowMillis .. ':' .. requestId)
redis.call('EXPIRE', KEYS[1], math.ceil(windowMillis / 1000))
return 1
