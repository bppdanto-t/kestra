<template>
    <div class="button-top">
        <el-button
            :icon="ContentSave"
            @click="$emit('save', source)"
            :type="buttonType"
            :disabled="!allowSaveUnchanged && source === initialSource"
        >
            {{ $t("save") }}
        </el-button>
    </div>
    <el-row>
        <el-col :span="24">
            <editor
                @save="(allowSaveUnchanged || source !== initialSource) ? $emit('save', $event) : undefined"
                v-model="source"
                schema-type="dashboard"
                lang="yaml"
                @update:model-value="source = $event"
                @cursor="updatePluginDocumentation"
                :creating="true"
                :read-only="false"
                :navbar="false"
            />
        </el-col>
    </el-row>
</template>

<script>
    import Editor from "../../inputs/Editor.vue";
    import YamlUtils from "../../../utils/yamlUtils.js";
    import ContentSave from "vue-material-design-icons/ContentSave.vue";

    export default {
        computed: {
            ContentSave() {
                return ContentSave
            },
            YamlUtils() {
                return YamlUtils
            },
            buttonType() {
                if (this.errors) {
                    return "danger";
                }

                return this.warnings
                    ? "warning"
                    : "primary";
            }
        },
        emits: ["save"],
        props: {
            allowSaveUnchanged: {
                type: Boolean,
                default: false
            },
            initialSource: {
                type: String,
                default: undefined
            }
        },
        components: {
            Editor
        },
        methods: {
            updatePluginDocumentation(event) {
                const taskType = YamlUtils.getTaskType(
                    event.model.getValue(),
                    event.position
                );
                const pluginSingleList = this.$store.getters["plugin/getPluginSingleList"];
                if (taskType && pluginSingleList && pluginSingleList.includes(taskType)) {
                    this.$store.dispatch("plugin/load", {cls: taskType}).then((plugin) => {
                        this.$store.commit("plugin/setEditorPlugin", plugin);
                    });
                } else {
                    this.$store.commit("plugin/setEditorPlugin", undefined);
                }
            }
        },
        data() {
            return {
                source: this.initialSource,
                errors: undefined,
                warnings: undefined
            }
        },
        beforeUnmount() {
            this.$store.commit("plugin/setEditorPlugin", undefined);
        }
    };
</script>
