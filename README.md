## BraveDNS + Firewall for Android

An [OpenSnitch](https://github.com/evilsocket/opensnitch)-inspired firewall and network monitor + a [pi-hole](https://github.com/pi-hole/pi-hole)-inspired DNS over HTTPS client with blocklists.

In other words, BraveDNS has two primary modes, DNS and Firewall. The DNS mode routes all DNS traffic generated by installed apps from their mobile device to one of two DNS over HTTPS resolvers (Cloudflare and AdGuard). The Firewall mode lets the user deny internet-access to entire applications based on events like screen-on / screen-off, app-foreground / app-background, connected to unmetered-connection / metered-connection / always; or based on play-store defined categories like Social, Games, Utility, Productivity; or additionally, based on user-defined blacklist.

### Firewall

The firewall is it doesn't really care about the connections per se rather what's making those connections. This is different from the traditional firewalls but in-line with [Little Snitch](https://www.obdev.at/products/littlesnitch/index.html), [LuLu](https://objective-see.com/products/lulu.html), [Glasswire](https://glasswire.com/) and others.

Currently, per app connection mapping is implemented by capturing UDP and TCP connections managed by tun2socks-layer (written in golang) and asking [ConnectivityService for the owner](https://developer.android.com/about/versions/10/privacy/changes#proc-net-filesystem). No `procfs` business here yet to track per-app connections like [NetGuard](https://github.com/M66B/NetGuard/) or OpenSnitch, but it is in the works. This means, both firewall and DNS are supported only on Android 10 and above until `procfs` tracking is added.

### Network Monitor

Not implemented yet, but it is almost done-- just a clean UI stands in the way (well, clean by our standards). A network monitor is a per-app report-card of sorts on when connections were made, how many were made, and to where. Tracking TCP has turned out to be so far straight-forward. DNS packets are trickier to track, and so a rough heuristic is used for now, which may not hold good in all cases. Coming soon.

### DNS over HTTPS client

Almost all of the network related code, including DNS over HTTPS split tunnel, is a very minimal fork of the excellent [Jigsaw-Code/outline-go-tun2socks](https://github.com/Jigsaw-Code/outline-go-tun2socks) written in golang. A majority of work is on the UI with other parts remaining same as on [Jigsaw-Code/Intra](https://github.com/Jigsaw-Code/Intra/), and so the functionality underneath is pretty much the same. The split-tunnel traps requests sent to the VPN's DNS endpoint and relays it to a DNS over HTTPS endpoint of the user's choosing (currently limited to Cloudflare's 1.1.1.1 and AdGuard DNS; but BraveDNS' own resolver will soon follow suit) and logs the end-to-end latency, time, the request query and it answer.

### What BraveDNS is not

BraveDNS is not an anonymity tool: It helps users tackle unabated censorship and surveillance but doesn't lay claim to protecting a user's identity at all times, if ever.

BraveDNS doesn't aim to be a feature-rich traditional firewall: It is more in-line with [Little Snitch](https://www.obdev.at/products/littlesnitch/index.html) than IP tables, say.

BraveDNS is not an anti-virus: BraveDNS may stop users from phising attacks, malware, scareware websites through its DNS-based blocklists, but it doesn't actively mitigate threats or even look for them or act on them, otherwise.

### What BraveDNS aspires to be

To turn Android devices into user-agents: Something that users can control as they please without requiring root-access. A big part of this, for an always-on, always-connected devices, is capturing network traffic and reporting it in a way that makes sense to the end-users who can then take a series of actions to limit their exposure but not necessiarly eliminate it. Take DNS for example-- for most if not all connections, apps send out a DNS request first, and by tracking just those one can glean a lot of intelligence about what's happening with the phone and which app's responsible.

To deliver the promise of open-internet for all: With the inevitable ESNI standardization and the imminent adoption of DNS over HTTPS and DNS over TLS across operating systems, we're that much closer to an open internet. Of course, Deep Packet Inspection remains a credible threat that can't be mitigated with this, but it is one example of delivering maximum impact (circumvents internet censorship in most countries) with minimal effort (not requiring a use of a VPN or IPFS, for example). BraveDNS would continue to make these technologies accessible in the most simplest way possible, especially the ones that get 90% of the way there with 10% effort.

## Development
1. Feel free to fork and send along a pull request for any reproducible bug fixes.
  1. The codebase is raw and is lacking documentation and comprehensive tests. If you need help, feel free to create a Wikipage to highlight the pain with building, testing, writing, committing code.
  2. Write descriptive commit messages that explain concisely the changes made. 
  3. Each commit must reference an open issue on the project. Again, this is to avoid duplicate work more than anything else.
2. If you plan to work on a feature, please create a github issue on the project first to kickstart the discussion before committing to doing any work. This is to make sure there isn't duplicated effort more than anything else.
3. Release cycles are undecided, but we're leaning towards bi-weekly once automated tests are up, whenever that may be.

## Tenents (unless you know better ones)
We aren't there yet, may never will be but these are some tenents for the project for the foreseebale future.

- Make it right, make it secure, make it resilient, make it fast. In that order.
- Easy to use, no-root, no-gimmicks features that are anti-censorship and anti-surveillance.
-- Easy to use: Any of the 2B+ Android users must be able to use it. Think CleanMaster / Instagram levels of ease-of-use. 
-- no-root: Shouldn't require root-access for any functionality added to it.
-- no-gimmicks: Misleading material bordering on scareware, for example.
-- anti-censorship: Features focused on helping bring an open internet to everyone, preferably in the most efficient way possible (both monetairly and technically).
-- anti-surveillance: As above, but features that further limit (may not necessairly eliminate) surveillance by apps.
- Incremental changes in balance with newer features.
-- For example, work on nagging UI issues or OEM specific bugs, must be taken up on equal weight to newer features, and a release must probably establish a good balance between the two. However; working on only incremental changes for a release is fine.
- Opinionated. Chip-away complexity. Do not expect users to require a PhD in Computer Science to use the app.
-- No duplicate functionality.
-- A concerted effort to not provide too many tunable knobs and settings. To err on the side of easy over simple.
- Ignore all tenents.
-- Common sense always takes over when tenents get in the way.
- Must be distributable on the PlayStore, at least some toned down version of it. 
-- [blokada](https://github.com/blokadaorg/blokada) has the right idea!
-- This unfortunately means on-device blocklists aren't possible; however, [https://www.cloudflare.com/teams-gateway/](Cloudflare Gateway)-esque cloud-based per-user blocklists get us the same functionality.
- Practice what you preach: Be obsessively private and secure.

## Backstory
Internet censorship (sometimes ISP-enforced and often times government-enforced), unabated dragnet surveillance (by pretty much every company and app) stirred us upon this path. The three of us university classmates, [Mohammed](https://www.linkedin.com/in/hussain-mohammed-2525a626/), [Murtaza](https://www.linkedin.com/in/murtaza-aliakbar/), [Santhosh](https://www.linkedin.com/in/santhosh-ponnusamy-2b781244/), got together in late 2019 in the sleepy town of Coimbatore, India to do something about it. Our main gripe was there were all these wonderful tools that people could use but couldn't, either due to cost or due to inability to grok Computer specific jargon. A lot has happened since we started and a lot has changed but our focus has always been on Android and its 2B+ unsuspecting users. The current idea is a year old, and has been in the works for about 4 months now, with the pandemic derailing a bit of progress, and a bit of snafu with abandoning our previous version in favour of the current fork, which we aren't proud of yet, but it is a start. All's good now that we've won a grant from the [Mozilla Builders MVP program](https://builders.mozilla.community/) to go ahead and build this thing that we wanted to... do so faster... and not simply sleep our way through the execution. I hope you're excited but not as much as us that you quit your jobs for this like we did.

## License

[Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0).