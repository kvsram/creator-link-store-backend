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

@Service
public class ProductFileService {
  private static final long MAX_BYTES = 500L * 1024 * 1024;
  private static final Set<String> KINDS = Set.of("download", "lesson-video", "supporting-material");
  private final ProductRepository products;
  private final Path storageRoot;

  public ProductFileService(ProductRepository products,
      @Value("${app.storage-dir:./data/uploads}") String storageDir) {
    this.products = products;
    this.storageRoot = Path.of(storageDir).toAbsolutePath().normalize();
  }

  public Map<String, Object> upload(long creatorId, long productId, String kind, MultipartFile file) {
    if (products.findOwned(creatorId, productId).isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found");
    if (!KINDS.contains(kind)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported file kind");
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
    return products.addFile(productId, original, objectKey,
        file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
        file.getSize(), kind);
  }

  public List<Map<String, Object>> files(long creatorId, long productId) {
    if (products.findOwned(creatorId, productId).isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found");
    return products.files(creatorId, productId);
  }
}
