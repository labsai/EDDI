import { makeStyles } from '@material-ui/core/styles';
import clsx from 'clsx';
import * as _ from 'lodash';
import * as React from 'react';
import { DragDropContext, Draggable, Droppable } from 'react-beautiful-dnd';
import { useSelector } from 'react-redux';
import modalActionDispatchers from '../../../scripts/actions/ModalActionDispatchers';
import { historyPush } from '../../../scripts/history';
import {
  BLACK_COLOR,
  BLUE_COLOR,
  WHITE_COLOR,
} from '../../../styles/DefaultStylingProperties';
import { isChatOpenedSelector } from '../../selectors/ChatSelectors';
import Plugin from '../PackageDetailView/PluginBoxes/Plugin';
import { IPackage, IPluginExtensions, IPlugins } from '../utils/AxiosFunctions';
import { isBotPage } from '../utils/helpers/getIdsFromPath';

const useStyles = makeStyles({
  pluginList: {
    display: 'grid',
    marginTop: '20px',
    gridGap: '20px',
    gridTemplateColumns: 'repeat(auto-fill, minmax(252px, 1fr))',
    minHeight: '5px',
    minWidth: '5px',
  },
  pluginListColumn: {
    display: 'flex',
    flexDirection: 'column',
  },
  draggableItem: {
    userSelect: 'none',
    display: 'flex',
    flexDirection: 'row',

    '&:hover': {
      '&:before': {
        backgroundColor: BLUE_COLOR,
      },
      '& button': {
        borderColor: BLUE_COLOR,
      },
    },
    '&:active': {
      '&:before': {
        backgroundColor: BLUE_COLOR,
      },
      '& button': {
        borderColor: BLUE_COLOR,
      },
    },

    '&:before': {
      backgroundColor: WHITE_COLOR,
      content: '"≡"',
      fontSize: '25px',
      margin: 'auto 0',
      color: BLACK_COLOR,
      display: 'flex',
      justifyContent: 'center',
      alignItems: 'center',
      padding: '0 3px 3px 4px',
      borderTopLeftRadius: '4px',
      borderBottomLeftRadius: '4px',
      marginTop: '10px',
      lineHeight: '25px',
      marginBottom: 'auto',
    },
  },
});

const getItemStyle = (draggableStyle, isDragging: boolean) => ({
  // styles we need to apply on draggables
  ...draggableStyle,
});

interface IProps {
  packagePayload: IPackage;
  plugins: IPluginExtensions[];
  packageId?: string;
  botId?: string;
  isChangingOrdering?: boolean;
  setPlugins: (plugins: IPluginExtensions[]) => void;
}

const PluginList: React.StatelessComponent<IProps> = (props: IProps) => {
  const classes = useStyles();
  const { isOpened: isChatOpened } = useSelector(isChatOpenedSelector);

  const openParallelConfigModal = (pluginResource?: string) => {
    modalActionDispatchers.showParallelConfigModal(
      props.packagePayload,
      pluginResource,
    );
  };

  const onDragEnd = (result) => {
    // dropped outside the list
    if (!result.destination) {
      return;
    }

    const reorderedItems = reorder(
      props.plugins,
      result.source.index,
      result.destination.index,
    );

    props.setPlugins(reorderedItems);
  };

  // reordering the result
  const reorder = (
    list: IPluginExtensions[],
    startIndex: number,
    endIndex: number,
  ) => {
    const result = Array.from(list);
    const [removed] = result.splice(startIndex, 1);
    result.splice(endIndex, 0, removed);

    return result;
  };

  if (props.isChangingOrdering) {
    return (
      <DragDropContext onDragEnd={onDragEnd}>
        <Droppable droppableId="droppable">
          {(provided) => {
            return (
              !_.isEmpty(props.plugins) && (
                <div
                  className={clsx(classes.pluginList, classes.pluginListColumn)}
                  ref={provided.innerRef}
                  {...provided.droppableProps}>
                  {props.plugins.map((plug, key) => (
                    <Draggable
                      key={plug.type + key}
                      draggableId={plug.type + key}
                      index={key}>
                      {(provided, snapshot) => (
                        <div>
                          <div
                            ref={provided.innerRef}
                            {...provided.dragHandleProps}
                            {...provided.draggableProps}
                            style={getItemStyle(
                              provided.draggableProps.style,
                              snapshot.isDragging,
                            )}
                            className={classes.draggableItem}>
                            <Plugin
                              key={key}
                              pluginType={plug}
                              pluginResource={plug.config.uri || ''}
                              editDisabled={true}
                              packageId={props.packageId}
                              botId={props.botId}
                              openParallelConfigModal={() => {
                                openParallelConfigModal(plug.config.uri);
                                historyPush(`${location.pathname}`, [
                                  `packageId=${props.packageId}`,
                                ]);
                              }}
                            />
                          </div>
                          {provided.placeholder}
                        </div>
                      )}
                    </Draggable>
                  ))}
                  {provided.placeholder}
                </div>
              )
            );
          }}
        </Droppable>
      </DragDropContext>
    );
  } else {
    return (
      <div>
        {!_.isEmpty(props.plugins) && (
          <div
            className={clsx(
              classes.pluginList,
              isChatOpened ? classes.pluginListColumn : null,
            )}>
            {props.plugins.map((plug, key) => (
              <Plugin
                key={key}
                pluginType={plug}
                pluginResource={plug.config.uri || ''}
                editDisabled={true}
                packageId={props.packageId}
                botId={props.botId}
                openParallelConfigModal={() => {
                  openParallelConfigModal(plug.config.uri);
                  historyPush(`${location.pathname}`, [
                    `packageId=${props.packageId}`,
                  ]);
                }}
              />
            ))}
          </div>
        )}
      </div>
    );
  }
};

export default React.memo(PluginList);
