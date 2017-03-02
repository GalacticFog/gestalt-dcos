[![Slack Status](https://chat.galacticfog.com/badge.svg)](https://chat.galacticfog.com)

# gestalt-dcos

A Universe one-click installer for the [gestalt-framework](http://www.galacticfog.com) on [DC/OS](https://dcos.io/).

## Known issues

Requires DC/OS 1.8.5 or later, as a result of these and other issues in DC/OS components.

* DC/OS beta 1.8.1 introduced a [breaking change](https://github.com/mesosphere/marathon/pull/4185) via Marathon 1.3.0-RC4 that affects the ability 
  of the gestalt-dcos Universe launcher to launch. This is resolved in Marathon 1.3.0-RC5, which is included in DC/OS 1.8.2.
* There is a [problem](https://groups.google.com/a/dcos.io/forum/#!msg/users/bKv9mucQBi0/H5VUg17nAAAJ) in DC/OS 1.7 regarding iptables, causing connectivity problems between
  services using VIPs. Because the gestalt-framework services are deployed using VIPs, this issue can cause problems between services. It manifests in a number of different ways,
  but the most notable are timeouts and `An I/O error occurred while sending to the backend` errors. The solution is to apply the sysctl settings from [this DC/OS
patch](https://github.com/dcos/dcos/blob/master/packages/minuteman/build#L31-L33). This seems to be resolved in DC/OS 1.8.x.
