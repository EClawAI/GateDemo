# Redis Key Design

This document describes all Redis keys used in the GateDemo project, their TTL settings, and recommendations for `maxmemory` and eviction policies.

## Key Catalog

| Key Pattern | TTL | Service | Purpose |
|-------------|-----|---------|---------|
| `token:blacklist:{jti}` | Matches token expiry | gate-service | JWT blacklist for logout/invalidation |
| `gate:{gateId}:heartbeat` | 120s | login-service | Gate instance heartbeat (aliased as `gate:online:{gateId}`) |
| `player:session:{playerId}` | Configurable | Various | Player session data; TTL via config |
| `game:status:{gameId}` | 120s | game-service | Game server status (online count, etc.) |

### Key Details

#### `token:blacklist:{jti}`
- **Format**: `token:blacklist:{jti}` where `jti` is the JWT ID (unique per token)
- **Value**: `"1"` (marker)
- **TTL**: Matches the token expiry; when the token would naturally expire, the blacklist entry expires too
- **Service**: gate-service (`TokenBlacklistService`)
- **Notes**: Prevents use of revoked tokens until their natural expiry

#### `gate:{gateId}:heartbeat`
- **Format**: `gate:online:{gateId}` (implementation key; equivalent to design key `gate:{gateId}:heartbeat`)
- **Value**: Online player count
- **TTL**: 120 seconds (recommended); heartbeat refreshes TTL periodically (e.g. every 30–60s)
- **Service**: login-service (`GateService`)
- **Notes**: Used for Gate instance discovery; missing key indicates gate offline

#### `player:session:{playerId}`
- **Format**: `player:session:{playerId}` or related patterns (e.g. `player:lastgame:{playerId}`)
- **Value**: Session or last-game data
- **TTL**: Configurable (e.g. 7 days for login record)
- **Service**: login-service, gate-service
- **Notes**: TTL should align with business session length

#### `game:status:{gameId}`
- **Format**: `game:status:{gameId}`
- **Value**: `{status}:{online}:{lastUpdateTime}` (e.g. `2:1500:1709900000000`)
- **TTL**: 120 seconds; heartbeat sync refreshes TTL (e.g. every 30s)
- **Service**: game-service (`GameStatusService`)
- **Notes**: Status values: 0=not started, 1=started not login, 2=can login

### Additional Keys

| Key Pattern | TTL | Service | Purpose |
|-------------|-----|---------|---------|
| `game:registry:{gameId}` | Configurable (default 60s) | game-service | Game instance registration for discovery |
| `player:lastgame:{playerId}` | 7 days | login-service | Last login game for routing |
| `game:message:queue` (Stream) | N/A | gate-service | Offline message queue |
| `game:message:offline:{playerId}` (Stream) | N/A | gate-service | Per-player offline messages |

## maxmemory and Eviction Policy

### Recommended maxmemory

Set `maxmemory` to approximately **75% of available physical memory** for the Redis process to leave headroom for the OS and other processes.

Example (8GB host):

```conf
maxmemory 6gb
```

### Recommended Eviction Policy

For GateDemo, all important keys use TTL. Recommended policies:

| Policy | Use Case |
|--------|----------|
| **volatile-ttl** | Preferred when all keys have TTL; evicts keys with TTL closest to expiry |
| **allkeys-lru** | Alternative when some keys may not have TTL; evicts least recently used keys |

**Recommended**: `volatile-ttl` if all keys have TTL; otherwise `allkeys-lru`.

```conf
maxmemory-policy volatile-ttl
```

### Example redis.conf

```conf
# Memory limit (adjust per host)
maxmemory 6gb
maxmemory-policy volatile-ttl

# Optional: RDB/AOF for persistence
save 900 1
save 300 10
save 60 10000
appendonly no
```

### Docker Compose Example

```yaml
redis:
  image: redis:7-alpine
  command: redis-server --maxmemory 512mb --maxmemory-policy volatile-ttl
  # ...
```

## TTL Best Practices

1. **Token blacklist**: Set TTL equal to token remaining lifetime.
2. **Heartbeat keys**: TTL = 2× heartbeat interval (e.g. 120s for 30–60s heartbeat).
3. **Session keys**: TTL should match business session length (e.g. 7 days).
4. **Offline message keys**: TTL of 7 days (604800 seconds) recommended.
