# Phase 1 Platform Recovery Smoke

- Target: admin1@192.168.0.41
- Healthy Nacos registrations verified before and after Nacos restart: 9/9.
- Gateway host port 8081 is published only on 127.0.0.1 through the onecard-ingress network.
- All nine application containers were stopped and restarted; healthy Nacos registrations recovered: 9/9.
- Account service restart returned a healthy Nacos registration.
- Anonymous protected Gateway request returned HTTP 401.
- Temporary Compose project, volumes, images, Docker context, and SSH tunnel are removed by the script.
