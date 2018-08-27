/*
 * Copyright 2016 OpenMarket Ltd
 * Copyright 2017 Vector Creations Ltd
 * Copyright 2018 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.matrix.androidsdk.sync;

import org.matrix.androidsdk.rest.model.MatrixError;
import org.matrix.androidsdk.rest.model.sync.SyncResponse;

/**
 * Interface to implement to listen to the event thread.
 */
public interface EventsThreadListener {
    /**
     * Call when a sync request has been performed with the API V2.
     *
     * @param response     the response (can be null)
     * @param fromToken    the start token
     * @param isCatchingUp true if a catchup is on progress
     */
    void onSyncResponse(SyncResponse response, String fromToken, boolean isCatchingUp);

    /**
     * The sync has encountered an error
     *
     * @param matrixError the matrix error
     */
    void onSyncError(final MatrixError matrixError);

    /**
     * A configuration error has been received.
     *
     * @param matrixErrorCode the matrix error code
     */
    void onConfigurationError(String matrixErrorCode);
}
