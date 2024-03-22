# Declarative manifest source

This is a generic source that takes the declarative manifest via a key `__injected_declarative_manifest` of its config.

## Execution
This entrypoint is used for connectors created by the connector builder. These connector's spec is defined in their manifest, which is defined in the config's "__injected_declarative_manifest" field. This allows this entrypoint to be used with any connector manifest.

The spec operation is not supported because the config is not known when running a spec.

## Local development

#### Building

When running a connector locally, you will need to make sure that the CDK generated artifacts are built. Run
```bash
# from airbyte-cdk/python
poetry install
poetry run poe build
```

### Locally running the connector

See `pokeapi_config.json` for an example of a config file that can be passed into the connector.

```bash
# from /airbyte-cdk/python/source-declarative-manifest
poetry run python main.py check --config secrets/config.json
poetry run  python main.py discover --config secrets/config.json
poetry run python main.py read --config secrets/config.json --catalog integration_tests/configured_catalog.json
```

### Locally running the connector docker image

#### Use `airbyte-ci` to build your connector
The Airbyte way of building this connector is to use our `airbyte-ci` tool.
You can follow install instructions [here](https://github.com/airbytehq/airbyte/blob/master/airbyte-ci/connectors/pipelines/README.md#L1).
Then running the following command will build your connector:
```bash
airbyte-ci connectors --name source-declarative-manifest build
```

Once the command is done, you will find your connector image in your local docker registry: `airbyte/source-declarative-manifest:dev`.

##### Customizing our build process
When contributing on our connector you might need to customize the build process to add a system dependency or set an env var.
You can customize our build process by adding a `build_customization.py` module to your connector.
This module should contain a `pre_connector_install` and `post_connector_install` async function that will mutate the base image and the connector container respectively.
It will be imported at runtime by our build process and the functions will be called if they exist.

Here is an example of a `build_customization.py` module:
```python
from __future__ import annotations

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    # Feel free to check the dagger documentation for more information on the Container object and its methods.
    # https://dagger-io.readthedocs.io/en/sdk-python-v0.6.4/
    from dagger import Container


async def pre_connector_install(base_image_container: Container) -> Container:
    return await base_image_container.with_env_variable("MY_PRE_BUILD_ENV_VAR", "my_pre_build_env_var_value")

async def post_connector_install(connector_container: Container) -> Container:
    return await connector_container.with_env_variable("MY_POST_BUILD_ENV_VAR", "my_post_build_env_var_value")
```

#### Build your own connector image
This connector is built using our dynamic built process in `airbyte-ci`.
The base image used to build it is defined within the metadata.yaml file under the `connectorBuildOptions`.
The build logic is defined using [Dagger](https://dagger.io/) [here](https://github.com/airbytehq/airbyte/blob/master/airbyte-ci/connectors/pipelines/pipelines/builds/python_connectors.py).
It does not rely on a Dockerfile.

If you would like to patch our connector and build your own a simple approach would be to:

1. Create your own Dockerfile based on the latest version of the connector image.
```Dockerfile
FROM airbyte/source-declarative-manifest:latest

COPY . ./airbyte/integration_code
RUN pip install ./airbyte/integration_code

# The entrypoint and default env vars are already set in the base image
# ENV AIRBYTE_ENTRYPOINT "python /airbyte/integration_code/main.py"
# ENTRYPOINT ["python", "/airbyte/integration_code/main.py"]
```
Please use this as an example. This is not optimized.

2. Build your image:
```bash
docker build -t airbyte/source-declarative-manifest:dev .
# Running the spec command against your patched connector
docker run airbyte/source-declarative-manifest:dev spec
```
#### Run

Then run any of the connector commands as follows:

```
docker run --rm -v $(pwd)/secrets:/secrets airbyte/source-declarative-manifest:dev check --config /secrets/config.json
docker run --rm -v $(pwd)/secrets:/secrets airbyte/source-declarative-manifest:dev discover --config /secrets/config.json
docker run --rm -v $(pwd)/secrets:/secrets -v $(pwd)/integration_tests:/integration_tests airbyte/source-declarative-manifest:dev read --config /secrets/config.json --catalog /integration_tests/configured_catalog.json
```