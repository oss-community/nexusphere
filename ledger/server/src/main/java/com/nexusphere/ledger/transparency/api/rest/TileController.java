package com.nexusphere.ledger.transparency.api.rest;

import com.nexusphere.ledger.chain.LogTiles;
import com.nexusphere.ledger.server.web.LedgerException;
import com.nexusphere.ledger.transparency.application.LogTileService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/public/v1/log")
class TileController {

    private static final String PREFIX = "/public/v1/log/";
    private static final CacheControl IMMUTABLE = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();

    private final LogTileService tiles;

    TileController(LogTileService tiles) {
        this.tiles = tiles;
    }

    @GetMapping("/tile/**")
    ResponseEntity<byte[]> tile(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String path = uri.substring(uri.indexOf(PREFIX) + PREFIX.length());
        LogTiles.Tile tile = LogTiles.parse(path).orElseThrow(() -> LedgerException.notFound("A log tile at " + path));
        byte[] body = tiles.tile(tile).orElseThrow(() -> LedgerException.notFound("A log tile at " + path));
        return ResponseEntity.ok().cacheControl(IMMUTABLE).contentType(MediaType.APPLICATION_OCTET_STREAM).body(body);
    }
}
