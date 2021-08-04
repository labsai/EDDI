import clsx from 'clsx';
import * as _ from 'lodash';
import * as React from 'react';
import { DragDropContext, Draggable, Droppable } from 'react-beautiful-dnd';
import { useSelector } from 'react-redux';
import { isChatOpenedSelector } from '../../selectors/ChatSelectors';
import { useStyles as useDragableStyles } from '../BotDetailView/PluginList';
import { IPackage, IPluginExtensions } from '../utils/AxiosFunctions';
import PluginHelper from '../utils/helpers/PluginHelper';
import { hasExtensions } from '../utils/helpers/PluginParser';
import reorder from '../utils/helpers/PluginsReordering';
import { IOptions } from './PackageView';
import useStyles from './PackageView.styles';
import Plugin from './PluginBoxes/Plugin';
import PluginWithExtension from './PluginBoxes/PluginWithExtensions';

interface IPackagePluginListProps {
  packagePayload: IPackage;
  plugins: IPluginExtensions[];
  isChangingOrdering?: boolean;
  readOnly: boolean;
  setPlugins: (plugins: IPluginExtensions[]) => void;
  deletePlugin: (extensionKey: number) => void;
  updatePlugin: (pluginKey: number, newPlugin: IOptions) => void;
  openParallelConfigModal: (pluginResource?: string) => void;
}

const PackagePluginList = ({
  plugins,
  packagePayload,
  readOnly,
  isChangingOrdering,
  setPlugins,
  deletePlugin,
  updatePlugin,
  openParallelConfigModal,
}: IPackagePluginListProps) => {
  const { isOpened: isChatOpened } = useSelector(isChatOpenedSelector);
  const classes = useStyles();
  const dnDclasses = useDragableStyles();

  const onDragEnd = (result) => {
    // dropped outside the list
    if (!result.destination) {
      return;
    }

    const reorderedItems = reorder(
      plugins,
      result.source.index,
      result.destination.index,
    );

    setPlugins(reorderedItems);
  };

  const getItemStyle = (draggableStyle) => ({
    ...draggableStyle,
  });

  const isCurrentVersion =
    packagePayload.version === packagePayload.currentVersion;

  return (
    <DragDropContext onDragEnd={onDragEnd}>
      <Droppable droppableId="droppable">
        {(provided) => {
          return (
            !!plugins &&
            !_.isEmpty(plugins) && (
              <div>
                {plugins
                  .filter((p) => hasExtensions(p))
                  .map((ext, key) => (
                    <PluginWithExtension
                      key={key}
                      pluginType={ext}
                      pluginResource={PluginHelper.getResource(ext)}
                      deletePlugin={deletePlugin}
                      updatePlugin={updatePlugin}
                      openParallelConfigModal={openParallelConfigModal}
                      editDisabled={!isCurrentVersion || readOnly}
                    />
                  ))}
                <div
                  className={clsx(
                    classes.pluginList,
                    isChatOpened || isChangingOrdering
                      ? classes.pluginListColumn
                      : null,
                  )}
                  ref={provided.innerRef}
                  {...provided.droppableProps}>
                  {plugins
                    .filter((p) => !hasExtensions(p))
                    .map((ext, key) => (
                      <Draggable
                        key={ext.type + key}
                        draggableId={ext.type + key}
                        index={key}>
                        {(provided) => (
                          <div>
                            <div
                              ref={provided.innerRef}
                              {...provided.dragHandleProps}
                              {...provided.draggableProps}
                              style={getItemStyle(
                                provided.draggableProps.style,
                              )}
                              className={
                                isChangingOrdering
                                  ? clsx(
                                      dnDclasses.draggableItem,
                                      dnDclasses.draggableItemCloseButton,
                                    )
                                  : undefined
                              }>
                              <Plugin
                                key={key}
                                pluginType={ext}
                                packageId={packagePayload.id}
                                deletePlugin={deletePlugin}
                                pluginResource={PluginHelper.getResource(ext)}
                                updatePlugin={updatePlugin}
                                openParallelConfigModal={() =>
                                  openParallelConfigModal(
                                    PluginHelper.getResource(ext),
                                  )
                                }
                                editDisabled={!isCurrentVersion || readOnly}
                              />
                            </div>
                            {provided.placeholder}
                          </div>
                        )}
                      </Draggable>
                    ))}
                  {provided.placeholder}
                </div>
              </div>
            )
          );
        }}
      </Droppable>
    </DragDropContext>
  );
};

export default PackagePluginList;
