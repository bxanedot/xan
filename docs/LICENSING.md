# Licensing notes

## XAN source

Original XAN source code is licensed under **GNU GPL version 3 only** (`GPL-3.0-only`). The repository's `LICENCE` file contains the complete license text. The license shown in **Settings → About** identifies the XAN-authored source.

Third-party code, artwork, and libraries keep their own licenses and notices. The XAN license does not replace those terms.

## Bundled and linked components

- **FFTW 3.3.10** is included as an upstream source archive at `mobile/android/app/src/main/cpp/vibrafp/third_party/fftw-3.3.10.tar.gz`. FFTW is licensed under GPL version 2 or, at the recipient's option, any later version; see its `COPYING` file inside the archive and the [upstream license page](https://www.fftw.org/doc/License-and-Copyright.html).
- **Google Play Services Cast** is an optional Android integration bundled in the all-features APK. Its Maven POM declares the Android Software Development Kit License, whose terms are available at [developer.android.com/studio/terms](https://developer.android.com/studio/terms.html). Those separate terms continue to apply. Do not read the About screen's GPL label as replacing the Cast SDK terms or as a blanket claim that every byte in the APK is GPL-licensed.
- **React Simple Icons** brand artwork is accompanied by its upstream MIT notice in each platform's `branding/xan-icon-pack/service-brands/` directory.
- **JavaScript dependencies** retain their individual license metadata in `web/package-lock.json` and `backend/listen-together/package.json` / installed package metadata.

Before redistributing a build that bundles third-party SDKs, keep the applicable notices with the build and review each dependency's terms. The entries above call out bundled components with distinct license obligations; they are not a complete inventory of every transitive dependency.
