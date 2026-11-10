# WildTempfly

Timed `/fly` in chosen worlds for Paper and Folia 26.1+ (Java 25). The fly time is stored in SQLite, or in MariaDB/MySQL so it follows players across the servers of a network.

## Commands

| Command | Permission | |
|---|---|---|
| `/fly` | `wildtempfly.fly` (everyone) | Fly on or off |
| `/fly time` | `wildtempfly.fly` | Fly time left |
| `/tempfly add <player> <time>` | `wildtempfly.admin.add` | Give fly time |
| `/tempfly remove <player> <time>` | `wildtempfly.admin.remove` | Take fly time |
| `/tempfly set <player> <time>` | `wildtempfly.admin.set` | Set the fly time |
| `/tempfly reset <player>` | `wildtempfly.admin.set` | Fly time to 0 |
| `/tempfly check <player>` | `wildtempfly.admin.check` | Show a player's fly time |
| `/tempfly reload` | `wildtempfly.admin.reload` | Reload `config.yml` and `lang.yml` |

Times: `90` (seconds), `30m`, `1h30m`, `2d`. `/wildtempfly` is an alias of `/tempfly`. `wildtempfly.admin` gives every admin node, `wildtempfly.bypass.time` flies without using time, `wildtempfly.bypass.world` flies in every world.

## How it works

- `/fly` works in the worlds of `worlds` and turns off when the player leaves them.
- The time runs down every second while the player is flying (`count-only-while-flying`), warnings at `warnings`, the time left in the action bar.
- When the time is over, fly turns off and the next fall does no damage (`fall-protection`).

## Cross-server

Set `type: mariadb` and the same `table-prefix` in `database.yml` on every server. Each server only writes the seconds it used up and reads the current value back every `sync-interval` seconds, so time added on one server is never overwritten by another.

## PlaceholderAPI

| Placeholder | |
|---|---|
| `%wildtempfly_time%` | Fly time left, e.g. `1h 12m 5s` |
| `%wildtempfly_seconds%` | Fly time left in seconds |
| `%wildtempfly_enabled%` | `true` while fly is on |

## Files

| File | Content |
|---|---|
| `config.yml` | Worlds, countdown, warnings, fall protection, sounds, aliases |
| `lang.yml` | Every chat text (MiniMessage) |
| `database.yml` | SQLite or MariaDB, table prefix, sync interval |

## License

MIT, see [LICENSE](LICENSE).
