# RankApi

Paper 1.21.4 plugin that allows your website to grant LuckPerms ranks through an HTTP API.

## Build

Open GitHub Codespaces and run:

```bash
mvn clean package
```

Jar output:

```text
target/Rankapi-1.0.jar
```

## API

POST:

```text
http://SERVER_IP:8080/grant-rank
```

JSON:

```json
{
  "secret":"CHANGE_THIS_SECRET",
  "player":"Steve",
  "rank":"vip"
}
```

Allowed ranks:

- vip
- mvp
- legend

Change them inside RankApiPlugin.java.
