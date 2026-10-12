# Test fixtures

All data here is fake and exists only for tests.

- `syncthing/`: `config.xml` and REST responses captured from a throwaway Syncthing v2.1.7-rc.1
  instance (built from the submodule) with one folder and one remote device. Device ids, API key,
  paths, host name and IP addresses were replaced with fixed fake values (IPs from the RFC 5737
  documentation ranges).
- `certs/`: certificates and private keys generated with OpenSSL for `CertificateValidator`
  tests. They protect nothing.
