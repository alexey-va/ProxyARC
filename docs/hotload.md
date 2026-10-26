# ProxyARC lifecycle

Code replacement requires an ordinary authorized Velocity restart. The pinned
Velocity 3.3 plugin API has no supported plugin unload/load operation; a config
reload does not replace Java classes. No custom loader is embedded or certified.

Construction is passive. Proxy initialization installs references, scheduling,
modules and commands. A failed initialization retires the same owners as proxy
shutdown. Shutdown closes event admission, removes exact owned command metadata,
closes every module, cancels tasks and detaches logging listeners/appenders.
Failures remain explicit and cleanup of later owners still runs.

This contract does not claim safe fresh-classloader replacement. A future
supported loader integration must separately establish async operation drains,
late-callback fencing and repeated replacement in one process before changing
the restart requirement.

Reference: [Velocity 3.3 PluginManager API](https://jd.papermc.io/velocity/3.3.0/com/velocitypowered/api/plugin/PluginManager.html).
