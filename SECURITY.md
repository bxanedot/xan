# Found a security issue in XAN? 🔐

First, thank you for looking out for XAN and the people who use it. You don’t need a perfect report or a ready-made fix. A note about what you noticed is a helpful start, and we can work through the details from there.

This guide covers security issues in the XAN code in this repository: the Android app, website, Listen Together servers, and release workflows.

## Let us know privately

The best way to report a vulnerability is GitHub's private report form:

**[Open a private vulnerability report](https://github.com/bxanedot/xan/security/advisories/new)**

One heads-up: GitHub private vulnerability reporting is currently **disabled** for this repository, so the form will not accept a report yet. A maintainer can switch it on in the repository's **Settings → Code security and analysis → Private vulnerability reporting**.

Until that is enabled, please don’t post technical details in a public issue, discussion, pull request, or social post. You can use a contact method listed on the [maintainer's GitHub profile](https://github.com/bxanedot) to ask for a private channel. Keep the first message general; share steps or exploit details only after a private channel is confirmed.

## What should I include?

Share whatever you can safely share. Even a short description is useful. If possible, tell us:

- **Where you saw it:** the app screen, website, server, workflow, version, or commit.
- **What happened:** what you did, what you expected, and what happened instead.
- **How to repeat it:** a small set of steps using an account or test data you’re allowed to use.
- **Why it could matter:** for example, could someone see another person’s data or change something they shouldn’t?
- **Helpful evidence:** a small proof of concept or a screenshot/log with private details removed.

If a template helps, you can copy this:

```text
Affected component and version:
What I noticed:
Steps to reproduce:
What I expected:
What happened instead:
Possible impact:
Evidence (with private details removed):
```

Please never send passwords, API keys, access tokens, signing credentials, or another person’s private data. If you found an exposed secret, tell us what kind of secret and where it appeared—leave the actual value out of the report. Check logs and screenshots for secrets before sharing them too.

## What kinds of issues should I report?

Please get in touch when you think a flaw could let someone do something they should not be able to do. For example:

- Join a private listening room, control playback, or change a queue without permission.
- See someone else’s private information, credentials, or session tokens.
- Make XAN handle a download, file, or music-provider response in an unsafe way.
- Use a web, Android, network, or release-workflow flaw to access data or take actions as another user.
- Cause code to run, read files, or change data through a weakness in XAN.
- Exploit a dependency issue through a part of XAN that users can actually reach.

If you aren’t sure whether something is security-related, it’s okay to ask for a private reporting route first. Ordinary crashes, visual bugs, and feature ideas are a better fit for the [regular issue tracker](https://github.com/bxanedot/xan/issues). If the problem belongs to a music provider or hosting service, please let that provider know as well.

## Please test gently

Use only devices, accounts, services, and data you own or have permission to test. A test account or local copy is a good choice. Please avoid opening or changing another person’s data, deleting data, disrupting a live service, running load tests against other users, or contacting people with deceptive messages.

If you’re not sure a test is safe, pause and ask for a test environment. Please don’t publish an unfixed vulnerability or exploit details while it is being looked into.

## What happens next?

Maintainers will review the report, try to reproduce the issue, and decide what fix or mitigation is appropriate. Timing depends on the issue and maintainer availability, so we can’t promise a fixed response deadline. We’ll aim to coordinate any public notice with a fix or practical mitigation, so users have useful next steps when details are shared.

## Which versions get security fixes?

Fixes focus on the latest stable release and the current `main` branch. Older versions may not receive fixes. You can find the newest version on the [XAN releases page](https://github.com/bxanedot/xan/releases); updating is the best way to get available security improvements.
