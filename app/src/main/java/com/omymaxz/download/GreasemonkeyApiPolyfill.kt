package com.omymaxz.download

object GreasemonkeyApiPolyfill {
    fun getPolyfill(script: UserScript): String {
        return """
        (function() {
            'use strict';

            const GM = {};
            window.GM = GM;

            // Callback handling for xmlHttpRequest
            if (!window.userscript_callbacks) {
                window.userscript_callbacks = {};
            }
            let callback_id = 0;

            // --- Value Storage ---
            GM.setValue = function(key, value) {
                return new Promise((resolve, reject) => {
                    try {
                        AndroidUserscriptAPI.setValue("${script.name}", key, JSON.stringify(value));
                        resolve();
                    } catch (e) {
                        reject(e);
                    }
                });
            };

            GM.getValue = function(key, defaultValue) {
                return new Promise((resolve, reject) => {
                    try {
                        const valStr = AndroidUserscriptAPI.getValue("${script.name}", key, JSON.stringify(defaultValue));
                        if (valStr === null) {
                            resolve(defaultValue);
                        } else {
                            resolve(JSON.parse(valStr));
                        }
                    } catch (e) {
                        reject(e);
                    }
                });
            };

            GM.deleteValue = function(key) {
                return new Promise((resolve, reject) => {
                    try {
                        AndroidUserscriptAPI.deleteValue("${script.name}", key);
                        resolve();
                    } catch (e) {
                        reject(e);
                    }
                });
            };

            GM.listValues = function() {
                return new Promise((resolve, reject) => {
                    try {
                        const listJson = AndroidUserscriptAPI.listValues("${script.name}");
                        resolve(JSON.parse(listJson));
                    } catch (e) {
                        reject(e);
                    }
                });
            };

            // --- XML HTTP Request ---
            GM.xmlHttpRequest = function(details) {
                const current_callback_id = callback_id++;

                return new Promise((resolve, reject) => {
                    window.userscript_callbacks[current_callback_id] = {
                        onload: function(response) {
                            if(details.onload) details.onload(response);
                            resolve(response);
                            delete window.userscript_callbacks[current_callback_id];
                        },
                        onerror: function(error) {
                            if(details.onerror) details.onerror(error);
                            reject(error);
                            delete window.userscript_callbacks[current_callback_id];
                        }
                    };

                    AndroidUserscriptAPI.xmlHttpRequest(JSON.stringify(details), current_callback_id.toString());
                });
            };

            // --- User Script ---
            try {
                ${script.script}
            } catch (e) {
                console.error('Userscript error in ${script.name}:', e);
            }
        })();
        """
    }
}
