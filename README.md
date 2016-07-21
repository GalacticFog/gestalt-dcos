# gestalt-dcos

A Universe one-click installer for the [gestalt-framework](http://www.galacticfog.com) on [DC/OS](https://dcos.io/).

## Known issues

* There is a [problem](https://groups.google.com/a/dcos.io/forum/#!msg/users/bKv9mucQBi0/H5VUg17nAAAJ) in DC/OS 1.7 regarding iptables, causing connectivity problems between
  services using VIPs. Because the gestalt-framework services are deployed using VIPs, this issue can cause problems between services. It manifests in a number of different ways,
  but the most notable are timeouts and `An I/O error occurred while sending to the backend` errors. The solution is to apply the sysctl settings from [this DC/OS
patch](https://github.com/dcos/dcos/blob/master/packages/minuteman/build#L28-L30).
