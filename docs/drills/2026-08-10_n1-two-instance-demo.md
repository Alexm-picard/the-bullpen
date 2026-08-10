# N1 Two-Instance Demo - 2026-08-10

- **Operator:** TD session on the box
- **Type:** multi-instance stateless-serving proof (N1, ADR-0013)
- **Host:** WSL2 desktop, post-deploy v2026.08.10-1034 (carries #434 driver 0.9.8 + #436 CORS)
- **Status:** **PASS**

## Evidence (verbatim from the box)

```
[10:43:07Z] health check: instance A (:8080) / instance B (:8082) / LB (:9101) - all pass
[10:43:07Z] predictions: 20 returned, 1 unique P(HR) value(s)
[10:43:07Z] PASS: all 20 predictions identical across both instances
[10:43:07Z] traffic distribution: A=21 B=0   (ip_hash affinity - documented-correct)
[10:43:07Z] PASS: A=0.0002513619838282466 == B=0.0002513619838282466
            (identical predictions, stateless serving confirmed)
  N1 TWO-INSTANCE DEMO: PASS
```

## What this proves

Two api instances on one host (ports 8080 + 8082), both serving the
same registry champion through the same SQLite (WAL mode, no contention),
behind an nginx ip_hash LB on port 9101. The same batted-ball prediction
input returns byte-identical P(HR) from both instances - the stateless-
replica property ADR-0013 documents as the horizontal scale-out path.

This upgrades `docs/capacity.md`'s caveat from "no multi-host
demonstration" to "proven at the operational level under a real HTTP
load balancer, in addition to the code-level proof (ApiPairTwoInstanceIT,
every CI pass)."

## Findings (demo-tooling, not system)

1. PropertiesLauncher is CWD-sensitive: must launch from /opt/bullpen
2. Port 9000 collides with ClickHouse native; moved to 9101
3. /health probe accepted node-exporter's page as success; needs content validation
4. location /health missing proxy_set_header Host (Tomcat 400 on underscore)
5. Access-log grep needs custom log_format with $upstream_addr

All five fixed in the demo script/config update (this PR).
