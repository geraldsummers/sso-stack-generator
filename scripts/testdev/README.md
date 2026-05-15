# Testdev Staging

`testdev` is the disposable staging profile for this stack. It is intentionally
two-layer isolation:

1. run the bundle inside the disposable Debian VM layer;
2. let `testdev` create a nested Docker-in-Docker daemon inside that VM.

Do not run `./testdev-up.sh` directly on the workstation host or on `latium`.
Host-level DinD is useful for emergency debugging only, but it is not a
production-readiness staging proof because workspace runtime ports,
host-gateway routing, and host resource boundaries differ from the deployed
labware model.

The scripts enforce this by default with `systemd-detect-virt`, the short
hostname `labware`, and a local Docker context. For a different disposable VM,
set `TESTDEV_ALLOWED_HOSTS` to its short hostname. Set
`TESTDEV_UNSAFE_ALLOW_NON_LABWARE_HOST=I_UNDERSTAND_THIS_TOUCHES_LOCAL_DOCKER`
only for deliberate local debugging.

Host roles:

- workstation: builds bundles and copies them out
- labware: runs disposable testdev DinD and destructive staging checks
- latium: runs the real stack deployment and production verification only

Expected operator flow:

```sh
./build.sh --manifest /path/to/site/manifest.json --profile testdev
rsync -av --delete ./dist/ gerald@labware.local:/tmp/sso-testdev-e2e/
./scripts/testdev/remote.sh up
./scripts/testdev/remote.sh verify kt-core
./scripts/testdev/remote.sh verify ts-e2e-visual
```

The testdev profile excludes only services that cannot run meaningfully in this
environment: GPU-bound model services, ingestion/publication workers, and the
external isolated-Docker-VM tunnel/proxy layer that the VM boundary replaces.
