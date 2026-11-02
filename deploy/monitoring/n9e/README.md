# N9E Configuration

`greptimedb-datasource.json` records the Prometheus-like query data source.
On a fresh MySQL volume, create it from the N9E data source page using this
configuration. Categraf writes metrics through N9E Pushgw to
`http://greptimedb:4000/v1/prometheus/write`.

`onecard-service-overview.json` is the initial dashboard template for all
`onecard-*` applications. `onecard-service-availability.json` is a disabled
alert rule template. Bind both to the GreptimeDB data source when importing.
Enable the alert only after the service labels and notification routing are
confirmed in the target environment.
