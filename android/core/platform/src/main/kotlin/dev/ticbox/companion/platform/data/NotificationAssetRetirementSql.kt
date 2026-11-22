package dev.ticbox.companion.platform.data

/**
 * Candidates come only from successfully replaced/withdrawn notification DataItems. Do not infer
 * retirement from age: an unreferenced asset may still be a staged draft for another workflow.
 */
internal object NotificationAssetRetirementSql {
    val clearBody = """UPDATE assets SET body=NULL
        WHERE scope_id=? AND digest=? AND body IS NOT NULL
          AND EXISTS (SELECT 1 FROM asset_acl p
            WHERE p.scope_id=assets.scope_id AND p.digest=assets.digest
              AND p.package_name=? AND p.package_digest=?)
          AND NOT EXISTS (SELECT 1 FROM item_assets r JOIN items i ON i.item_id=r.item_id
            WHERE i.scope_id=assets.scope_id AND r.digest=assets.digest AND i.deleted=0)
          AND NOT EXISTS (SELECT 1 FROM asset_acl other
            WHERE other.scope_id=assets.scope_id AND other.digest=assets.digest
              AND (other.package_name<>? OR other.package_digest<>?))""".trimIndent()
}
