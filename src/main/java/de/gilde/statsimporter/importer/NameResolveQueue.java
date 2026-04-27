package de.gilde.statsimporter.importer;

final class NameResolveQueue {

    private NameResolveRequest queuedRequest;

    synchronized boolean offer(String reason, Integer maxPerRunOverride) {
        NameResolveRequest request = new NameResolveRequest(reason, maxPerRunOverride);
        if (queuedRequest == null) {
            queuedRequest = request;
            return true;
        }
        queuedRequest = queuedRequest.merge(request);
        return false;
    }

    synchronized NameResolveRequest poll() {
        NameResolveRequest request = queuedRequest;
        queuedRequest = null;
        return request;
    }

    synchronized boolean hasQueuedRequest() {
        return queuedRequest != null;
    }

    record NameResolveRequest(String reason, Integer maxPerRunOverride) {

        NameResolveRequest {
            if (reason == null || reason.isBlank()) {
                reason = "queued";
            }
            if (maxPerRunOverride != null && maxPerRunOverride < 1) {
                maxPerRunOverride = 1;
            }
        }

        NameResolveRequest merge(NameResolveRequest other) {
            Integer mergedMax = max(maxPerRunOverride, other.maxPerRunOverride());
            return new NameResolveRequest(mergeReason(reason, other.reason()), mergedMax);
        }

        private Integer max(Integer left, Integer right) {
            if (left == null) {
                return right;
            }
            if (right == null) {
                return left;
            }
            return Math.max(left, right);
        }

        private String mergeReason(String left, String right) {
            if (left.equals(right)) {
                return left;
            }
            String merged = left + "+" + right;
            if (merged.length() <= 160) {
                return merged;
            }
            return "queued:multiple";
        }
    }
}
