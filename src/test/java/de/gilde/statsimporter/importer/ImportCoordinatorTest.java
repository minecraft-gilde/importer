package de.gilde.statsimporter.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImportCoordinatorTest {

    @Test
    void statsDirCandidatesUsePaper261PlayerStatsLayout() {
        Path worldContainer = Path.of("server");
        Path overworld = worldContainer.resolve("world");
        Path nether = worldContainer.resolve("world_nether");

        List<Path> candidates = ImportCoordinator.statsDirCandidates(
                worldContainer,
                List.of(overworld, nether)
        );

        assertEquals(
                List.of(
                        overworld.resolve("players").resolve("stats"),
                        nether.resolve("players").resolve("stats")
                ),
                candidates
        );
    }

    @Test
    void statsDirCandidatesFallBackToDefaultWorldWhenNoWorldIsLoaded() {
        Path worldContainer = Path.of("server");
        Path defaultWorld = worldContainer.resolve("world");

        List<Path> candidates = ImportCoordinator.statsDirCandidates(worldContainer, List.of());

        assertEquals(
                List.of(
                        defaultWorld.resolve("players").resolve("stats")
                ),
                candidates
        );
    }
}
