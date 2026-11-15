---
name: Bug report
about: PerfLens reported something wrong, missed a problem, or broke the app
labels: bug
---

**What happened**

**What you expected**

**Environment**
- Spring Boot version:
- JDK vendor and version:
- OS:
- Servlet or WebFlux:
- JDBC or R2DBC (and database):
- HTTP clients used (RestClient / RestTemplate / WebClient):

**Evidence**

Relevant part of `GET /actuator/perflens/report`, or the startup log line starting with `PerfLens is watching`.
Remove real data first: by default the report contains real SQL parameters and method arguments.
