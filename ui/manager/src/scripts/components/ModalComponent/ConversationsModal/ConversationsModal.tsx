import * as moment from 'moment';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import Options from '../../Assets/Buttons/Options';
import VersionSelectComponent from '../../Assets/VersionSelectComponent';
import { IBot } from '../../utils/AxiosFunctions';
import Parser from '../../utils/Parser';
import '../ModalComponent.styles.scss';
import useStyle from '../ViewJsonModal/ViewJsonModal.styles';
import ConversationList from './ConversationList';

interface IProps {
  bot: IBot;
}

const ConversationsModal = ({ bot }: IProps) => {
  const [selectedResource, setSelectedResource] = React.useState<string>(
    bot.resource,
  );
  const classes = useStyle();

  const selectVersion = (version: number) => {
    setSelectedResource(Parser.replaceResourceVersion(bot.resource, version));
  };

  return (
    <div>
      <div className={classes.header}>
        <div className={classes.topHeader}>
          <div className={classes.title}>{bot.name}</div>
          <VersionSelectComponent
            selectedVersion={bot.version}
            currentVersion={bot.currentVersion}
            selectVersion={selectVersion}
          />
          <div className={classes.centerFlex} />
          <div className={classes.options}>
            <Options descriptor={bot} data={bot.packages} />
          </div>
        </div>
        <div className={classes.bottomHeader}>
          <div className={classes.descriptionContainer}>
            <div className={classes.smallTitle}>{'Description'}</div>
            <div className={classes.smallText}>{bot.description}</div>
          </div>
          <div className={classes.dateContainer}>
            <div className={classes.smallTitle}>{'Created'}</div>
            <div className={classes.smallText}>
              {moment(bot.createdOn).format('DD.MM.YYYY')}
            </div>
          </div>
          <div className={classes.dateContainer}>
            <div className={classes.smallTitle}>{'Last modified'}</div>
            <div className={classes.smallText}>
              {moment(bot.lastModifiedOn).format('DD.MM.YYYY')}
            </div>
          </div>
        </div>
      </div>
      <ConversationList botResource={selectedResource} />
    </div>
  );
};

const ComposedConversationsModal: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('ConversationsModal'),
)(ConversationsModal);

export default ComposedConversationsModal;
