# Copyright (c) 2024 Airbyte, Inc., all rights reserved.

from dataclasses import dataclass
from typing import Any, MutableMapping

from airbyte_cdk.sources.declarative.transformations import RecordTransformation

@dataclass
class NotionUserTransformation(RecordTransformation):
  """
  Custom transformation that conditionally moves the data in owner.{owner_type} 
  to a new owner.info field when the record contains data for a "bot" type user.
  """

  def transform(self, record: MutableMapping[str, Any], **kwargs) -> MutableMapping[str, Any]:
    owner = record.get("bot", {}).get("owner")
    if owner:
      owner_type = owner.get("type")
      owner_info = owner.get(owner_type)
      if owner_type and owner_info:
        record["bot"]["owner"]["info"] = owner_info
        del record["bot"]["owner"][owner_type]
    return record
  

@dataclass
class NotionPageDatabaseTransformation(RecordTransformation):
  pass


@dataclass
class NotionBlocksTransformation(RecordTransformation):
  pass
