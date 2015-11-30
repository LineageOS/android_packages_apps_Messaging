/*
* Copyright (C) 2015 The CyanogenMod Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.cyanogenmod.messaging.lookup;

/**
 * <pre>
 *      Client interface for talking to lookup provider
 * </pre>
 */
public interface ILookupClient {

    /**
     * Will call for lookup
     *
     * Will automatically format to E164
     *
     * @param phoneNumber {@link String} not null or empty
     */
    void lookupInfoForPhoneNumber(String phoneNumber);

    /**
     * Will call for lookup and allow requery of possibly stale data
     *
     * @param phoneNumber {@link String} not null or empty
     * @param requery {@link Boolean}
     */
    void lookupInfoForPhoneNumber(String phoneNumber, boolean requery);

    /**
     * Will mark number as spam
     *
     * This will automatically format to E164
     *
     * @param phoneNumber {@link String} not null and not empty
     */
    void markAsSpam(String phoneNumber);

    /**
     * Check if spam reporting is available
     *
     * @return {@link Boolean}
     */
    boolean hasSpamReporting();

    /**
     * Get the display name of the provider
     *
     * @return {@link String} or null
     */
    String getProviderName();

    /**
     * Add a listener for a specific number
     *
     * @param number {@link String}
     * @param listener {@link LookupProviderManager.LookupProviderListener}
     */
    void addLookupProviderListener(String number,
                                   LookupProviderManager.LookupProviderListener listener);

    /**
     * Remove a listener for a specific number
     *
     * @param number {@link String}
     * @param listener {@link LookupProviderManager.LookupProviderListener}
     */
    void removeLookupProviderListener(String number,
                                             LookupProviderManager.LookupProviderListener listener);
}
