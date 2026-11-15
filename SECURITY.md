# Security

PerfLens records SQL statements, bind parameters and method arguments from the application it runs in, and serves them over an actuator endpoint. Two things follow from that:

- **Protect the endpoint.** `/actuator/perflens` follows your Spring Security rules and management port like any actuator endpoint. Don't expose it publicly.
- **Turn off value capture in production** with `perflens.capture.params=false`, or extend `perflens.capture.mask-names` to cover your sensitive fields.

## Reporting a vulnerability

Please report security problems privately, using **Report a vulnerability** on the repository's Security tab, rather than in a public issue. Examples: masking that can be bypassed, values leaking despite `capture.params=false`, or the endpoint being reachable when exposure is not configured.
