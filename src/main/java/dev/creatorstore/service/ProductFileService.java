package dev.creatorstore.service;

import dev.creatorstore.repository.ProductRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import dev.creatorstore.domain.ProductTypeConfiguration.Delivery;

@Service
public class ProductFileService {
  private static final long MAX_BYTES = 500L * 1024 * 1024;
  private static final Set<String> KINDS = Set.of("download", "lesson-video", "supporting-material");
  private final ProductRepository products;
  private final ProductConfigurationService configurations;
  private final Path storageRoot;

  public ProductFileService(ProductRepository products,
      ProductConfigurationService configurations,
      @Value("${app.storage-dir:./data/uploads}") String storageDir) {
    this.products = products;
    this.configurations = configurations;
    this.storageRoot = Path.of(storageDir).toAbsolutePath().normalize();
  }

  public Map<String, Object> upload(long creatorId, long productId, String kind, MultipartFile file) {
    var owned = products.findOwned(creatorId, productId);
    if (owned.isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found");
    String type = String.valueOf(owned.get(0).get("type"));
    if (!KINDS.contains(kind) || !allowedKinds(type).contains(kind))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "file kind is not supported for this product type");
    if (file.isEmpty() || file.getSize() > MAX_BYTES)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file must be between 1 byte and 500 MB");
    String original = Path.of(file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename()).getFileName().toString();
    String objectKey = creatorId + "/" + productId + "/" + UUID.randomUUID();
    Path destination = storageRoot.resolve(objectKey).normalize();
    if (!destination.startsWith(storageRoot)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid file path");
    try {
      Files.createDirectories(destination.getParent());
      Files.copy(file.getInputStream(), destination, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException exception) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "file could not be stored");
    }
    try {
      return products.addFile(productId, original, objectKey,
          file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
          file.getSize(), kind);
    } catch (RuntimeException databaseFailure) {
      try { Files.deleteIfExists(destination); } catch (IOException ignored) { /* best effort */ }
      throw databaseFailure;
    }
  }

  public List<Map<String, Object>> files(long creatorId, long productId) {
    if (products.findOwned(creatorId, productId).isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found");
    return products.files(creatorId, productId);
  }

  @Transactional
  public void delete(long creatorId, long productId, long fileId) {
    List<Map<String, Object>> rows = products.findOwnedFile(creatorId, productId, fileId);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "product file not found");
    Map<String, Object> file = rows.get(0);
    String type = String.valueOf(file.get("type"));
    String kind = String.valueOf(file.get("kind"));
    if ("published".equals(String.valueOf(file.get("status")))
        && Set.of("digital-download", "lead-magnet").contains(type)
        && "download".equals(kind)) {
      Map<String, Object> product = products.findOwned(creatorId, productId).get(0);
      var configuration = configurations.validate(type,
          configurations.parseSnapshot(product.get("configuration_json")));
      if (configuration instanceof Delivery delivery && "upload".equals(delivery.deliveryMode())
          && products.countFiles(creatorId, productId, "download") <= 1)
        throw new ResponseStatusException(HttpStatus.CONFLICT,
            "unpublish the product before deleting its only delivery file");
    }
    products.deleteFile(creatorId, productId, fileId);
    Path stored = storageRoot.resolve(String.valueOf(file.get("object_key"))).normalize();
    if (stored.startsWith(storageRoot)) {
      try { Files.deleteIfExists(stored); } catch (IOException ignored) { /* best effort cleanup */ }
    }
  }

  private static Set<String> allowedKinds(String type) {
    return switch (type) {
      case "digital-download", "lead-magnet" -> Set.of("download");
      case "course" -> Set.of("lesson-video", "supporting-material");
      default -> Set.of();
    };
  }
}
