# Wren:AM
[Wiki](https://github.com/WrenSecurity/wrenam/wiki) \|
[Google Groups **Mailing List**](https://groups.google.com/forum/#!forum/wren-security) \|
[Gitter **Chat**](https://gitter.im/WrenSecurity/Lobby)  
[![CDDL-licensed](https://img.shields.io/badge/license-CDDL-blue.svg)](license)
[![Build Status](https://semaphoreci.com/api/v1/wrensecurity/wrenam/branches/master/badge.svg)](https://semaphoreci.com/wrensecurity/wrenam)
[![Average time to resolve an issue](http://isitmaintained.com/badge/resolution/WrenSecurity/wrenam.svg)](http://isitmaintained.com/project/WrenSecurity/wrenam "Average time to resolve an issue")
[![Percentage of issues still open](http://isitmaintained.com/badge/open/WrenSecurity/wrenam.svg)](http://isitmaintained.com/project/WrenSecurity/wrenam "Percentage of issues still open")

Wren:AM is a CDDL-licensed, community fork of ForgeRock's OpenAM product. Though our project originated with code that ForgeRock previously released, we are not affiliated with ForgeRock in any way. 

ForgeRock no longer releases any of the most recent versions of their software under an open-source license. ForgeRock’s “Community Edition” versions are much older versions then their commercial offerings. Join our community for the latest and greatest. 

See [wrensecurity.org](https://wrensecurity.org/) and [timeforafork.com](http://www.timeforafork.com/) for more information.

## Preparing Your Build Environment
In order to build Wren:AM from source you need the following software installed:

* Java (OpenJDK) >= 1.7
* Apache Maven >= 3.1.0

## About This Branch (`master`)
This branch is for new development and experimental, bleeding-edge feature development. It is not guaranteed to be stable.

If you are interested in building or using a stable release, please examine the `sustaining/*` branches or download a tagged release.

This branch is based on code provided by ForgeRock (from [the "trunk"](https://stash.forgerock.org) under the CDDL license.

## Building Wren:AM
Wren:AM is fully buildable from source.

**If you have previously built OpenAM or other ForgeRock products from source, you are strongly encouraged to clear your local `~/.m2/repository` before building Wren:AM for the first time.**

This step is necessary because Wren artifacts in this version of AM have the same Maven artifact IDs that ForgeRock originally used for the corresponding artifacts, but our copies contain modifications to source from our Maven repositories. This was necessary because ForgeRock's repositories are no longer publicly accessible.

After clearing your local repository of conflicting ForgeRock artifacts, clone the `wrenam` repository and checkout the `master` branch:

```
$ git clone -b master https://github.com/WrenSecurity/wrenam.git
$ cd wrenam
$ mvn clean install
```

## How and Why to Contribute
Contributing to Wren:AM is easy! Please review our [contributor guidelines](https://github.com/WrenSecurity/wrensec-docs/wiki/Contributor-Guidelines).

By contributing, you are helping to shape Wren:AM to meet your needs. Your contributions make the product more secure, more robust, and more flexible -- for your needs now, and other's needs in the future. If you find a bug, or have an enhancement request, consider contributing toward a solution. Every PR helps.

## Past and Current Contributors
Wren:AM is a joint effort between _developers like you_, and the following Wren Security member organizations:
- [Orchitech Solutions, s.r.o.](https://orchi.tech/)

See the full list of [contributors](https://github.com/WrenSecurity/wrenam/graphs/contributors) who have participated in this project so far.

## License
This project is licensed under the Common Development and Distribution License (CDDL). The following text applies to both this file, and should also be included in all files in the project:

> The contents of this file are subject to the terms of the Common Development and  Distribution License (the License).
> You may not use this file except in compliance with the License.
>
> You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the specific language governing
> permission and limitations under the License.
>
> When distributing Covered Software, include this CDDL Header Notice in each file and include the License file at
> legal/CDDLv1.0.txt. If applicable, add the following below the CDDL Header, with the fields enclosed by brackets []
> replaced by your own identifying information: "Portions copyright [year] [name of copyright owner]".
>
> Copyright 2017-2018 Wren Security.

## Acknowledgments
The Wren Security team acknowledges the contributions made to the original OpenSSO and OpenAM products on which Wren:AM is based by the following organizations:

* Sun Microsystems
* Oracle
* ForgeRock

Although the Wren Security team acknowledges their contributions, the above organizations are in no way affiliated with Wren Security, Wren:AM, or any other Wren Security project.
