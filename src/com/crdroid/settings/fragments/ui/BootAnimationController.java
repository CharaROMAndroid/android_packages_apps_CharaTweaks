/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package com.evolution.settings.fragments.ui;

import android.content.Context;

import com.android.settings.core.BasePreferenceController;

import com.crdroid.settings.utils.BootAnimationUtils;

public class BootAnimationController extends BasePreferenceController {

    public BootAnimationController(Context context, String preferenceKey) {
        super(context, preferenceKey);
    }

    @Override
    public int getAvailabilityStatus() {
        // Hide on unsupported devices
        if (BootAnimationUtils.isBootAnimationSelectorDisabled()) {
            return UNSUPPORTED_ON_DEVICE;
        }
        // Return available if the device supports the feature
        return AVAILABLE;
    }
}
