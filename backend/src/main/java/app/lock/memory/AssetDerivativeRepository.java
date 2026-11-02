package app.lock.memory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetDerivativeRepository extends JpaRepository<AssetDerivative, UUID> {

    Optional<AssetDerivative> findByAssetIdAndVariant(UUID assetId, AssetDerivative.Variant variant);

    List<AssetDerivative> findByAssetIdIn(Collection<UUID> assetIds);

    boolean existsByAssetIdAndVariant(UUID assetId, AssetDerivative.Variant variant);
}
