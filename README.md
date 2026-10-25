<p align="center">
  <img src=".github/assets/readme-hero.png" alt="pohunek for Android: your agent sessions on the phone, over the NetBird overlay. Engraving of a farmhand napping under a tree while a small copper Android robot keeps watch over three server cabinets plowing on their own." />
</p>

<p align="center">
  <a href="https://pohunek.dev/surfaces/android/">pohunek.dev/surfaces/android</a>
  ·
  <a href="https://pohunek.dev/docs/">Pohunek docs</a>
</p>

# pohunek-companion-android

Native Android companion for [Pohunek](https://github.com/zajca/pohunek):
remote session list with live state and an embedded terminal over the NetBird
overlay (Kotlin, Jetpack Compose).

Installing a release APK and publishing one: [docs/release.md](docs/release.md).

Status: pre-release. The latest build is on the
[releases page](https://github.com/pohunekd/pohunek-companion-android/releases/latest);
installing it is described in [docs/release.md](docs/release.md). The
[Android page on pohunek.dev](https://pohunek.dev/surfaces/android/) describes
the app and [docs/architecture.md](docs/architecture.md) how it works.

## Screenshots

<table>
  <tr>
    <td align="center" valign="top">
      <img src="docs/screenshots/session-list.png" alt="Pohunek Companion session list in the dark theme: a Needs you section with two blocked agent sessions above the working and idle sessions." width="240" /><br />
      Every session on every host, with the ones that need you at the top.
    </td>
    <td align="center" valign="top">
      <img src="docs/screenshots/by-state.png" alt="Pohunek Companion session list in the dark theme with the By state view selected: sessions in Needs you, Working and Idle sections." width="240" /><br />
      The same sessions grouped by what they are doing, so the ones waiting for you come first.
    </td>
    <td align="center" valign="top">
      <img src="docs/screenshots/filters.png" alt="Pohunek Companion session list in the dark theme with the Ended state chip on and one project selected, showing only that project's sessions." width="240" /><br />
      Narrow the list by state, project or host; here one project is shown with its ended sessions.
    </td>
  </tr>
  <tr>
    <td align="center" valign="top">
      <img src="docs/screenshots/groups.png" alt="Pohunek Companion My groups view in the dark theme: Needs you at the top, then the Dependencies group open with its session and the Docs and Tooling groups collapsed to a header with a count." width="240" /><br />
      Your own groups, shared with the desktop app, hold the sessions you sort into them.
    </td>
    <td align="center" valign="top">
      <img src="docs/screenshots/row-actions.png" alt="Pohunek Companion action sheet for one session in the dark theme, listing Open terminal, Details, Rename, New session here, Move to group, Open link, Stop and Remove." width="240" /><br />
      Every session row has an action sheet to open, rename, move, stop or remove it.
    </td>
    <td align="center" valign="top">
      <img src="docs/screenshots/session-details.png" alt="Pohunek Companion session details screen in the dark theme: identity, agent and location fields of one session, each with a Copy button." width="240" /><br />
      The identity, agent and location of one session, each field with a Copy button.
    </td>
  </tr>
  <tr>
    <td align="center" valign="top">
      <img src="docs/screenshots/new-session.png" alt="Pohunek Companion New session form in the dark theme with a project template selected, its agent chosen and its prompt filled in." width="240" /><br />
      Start a session on a host from a project template, with its agent and prompt filled in.
    </td>
    <td align="center" valign="top">
      <img src="docs/screenshots/hosts.png" alt="Pohunek Companion Hosts screen in the dark theme: one host with its address and a Connected status, and an Add a host button." width="240" /><br />
      The hosts the app follows, each with its connection state.
    </td>
    <td align="center" valign="top">
      <img src="docs/screenshots/settings.png" alt="Pohunek Companion settings screen in the dark theme: a list of tunables, each with its default value." width="240" /><br />
      Every tunable of the app in one list, with its default and a reset.
    </td>
  </tr>
  <tr>
    <td align="center" valign="top">
      <img src="docs/screenshots/terminal.png" alt="Pohunek Companion full-screen terminal attached to a session of a working agent, in the dark theme." width="240" /><br />
      A full-screen terminal attached to a running session.
    </td>
  </tr>
</table>


The Android robot is reproduced or modified from work created and shared by
Google and used according to terms described in the Creative Commons 3.0
Attribution License.
