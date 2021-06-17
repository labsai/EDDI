import clsx from 'clsx';
import * as moment from 'moment';
import * as React from 'react';
import ReactJson from 'react-json-view';
import { connect } from 'react-redux';
import ClipLoader from 'react-spinners/ClipLoader';
import { compose, pure, setDisplayName } from 'recompose';
import { BLUE_COLOR } from '../../../styles/DefaultStylingProperties';
import eddiApiActionDispatchers from '../../actions/EddiApiActionDispatchers';
import modalActionDispatchers from '../../actions/ModalActionDispatchers';
import { historyPush } from '../../history';
import { readOnlySelector } from '../../selectors/AuthenticationSelectors';
import { conversationSelector } from '../../selectors/ConversationSelectors';
import WhiteButton from '../Assets/Buttons/WhiteButton';
import HomeButtonComponent from '../HomeButton/HomeButtonComponent';
import { IConversation } from '../utils/AxiosFunctions';
import { CONVERSATION_READY } from '../utils/helpers/ConversationHelper';
import Parser from '../utils/Parser';
import useStyles from './BotConversionView.styles';
import ConversationProperties from './ConversationTab/ConversationProperties';
import ConversationSteps from './ConversationTab/ConversationSteps';

interface IPublicProps {
  conversationId: string;
}

interface IPrivateProps extends IPublicProps {
  conversation: IConversation;
  isLoading: boolean;
  readOnly: boolean;
}

enum TabEnum {
  'conversationSteps',
  'json',
}

const BotConversationView = ({
  conversation,
  isLoading,
  readOnly,
  conversationId,
}: IPrivateProps) => {
  const [selectedTab, setSelectedTab] = React.useState<TabEnum>(
    TabEnum.conversationSteps,
  );

  const classes = useStyles();

  React.useEffect(() => {
    if (conversation) {
      fetchConversation();
    } else {
      eddiApiActionDispatchers.fetchConversationsAction(
        1,
        0,
        conversationId,
        null,
      );
    }
  }, [conversation]);

  const fetchConversation = () => {
    eddiApiActionDispatchers.fetchConversationAction(conversationId);
  };

  const endConversation = () => {
    modalActionDispatchers.showConfirmationModal(
      `Are you sure you want to end this conversation?`,
      null,
      () => eddiApiActionDispatchers.endConversationAction(conversationId),
    );
  };

  return (
    <div className={classes.content}>
      <HomeButtonComponent extraPath={'conversations'} />
      {isLoading && !conversation && (
        <div className={classes.loadingWrapper}>
          <ClipLoader color={BLUE_COLOR} />
        </div>
      )}
      {!!conversation && (
        <div>
          <div className={classes.header}>
            <div className={classes.topHeader}>
              <div
                className={classes.botName}
                onClick={() =>
                  historyPush(
                    `/botview/${Parser.getId(conversation.botResource)}`,
                    [`version=${Parser.getVersion(conversation.botResource)}`],
                  )
                }>
                {conversation.botName}
              </div>
              <div className={classes.botVersion}>{`V${Parser.getVersion(
                conversation.botResource,
              )}`}</div>
              <WhiteButton
                text={'End Conversation'}
                classes={{ button: classes.endConversationButton }}
                disabled={
                  conversation.conversationState !== CONVERSATION_READY ||
                  readOnly
                }
                onClick={endConversation}
              />
            </div>
            <div className={classes.bottomHeader}>
              <div className={classes.descriptor}>
                <div className={classes.title}>{'Environment'}</div>
                <div className={classes.descriptorContent}>
                  {conversation.environment}
                </div>
              </div>
              <div className={classes.descriptor}>
                <div className={classes.title}>{'Conversation state'}</div>
                <div className={classes.descriptorContent}>
                  {conversation.conversationState}
                </div>
              </div>
              <div className={classes.descriptor}>
                <div className={classes.title}>{'Last message'}</div>
                <div className={classes.descriptorContent}>
                  {moment(conversation.lastModifiedOn).fromNow()}
                </div>
              </div>
              <div className={classes.descriptor}>
                <div className={classes.title}>{'Created on'}</div>
                <div className={classes.descriptorContent}>
                  {moment(conversation.createdOn).format('DD.MM.YYYY')}
                </div>
              </div>
              <div className={classes.descriptor}>
                <div className={classes.title}>{'User id'}</div>
                {!!conversation.data && (
                  <div className={classes.descriptorContent}>
                    {conversation.data.userId}
                  </div>
                )}
              </div>
            </div>
          </div>
          <div className={classes.tabs}>
            <div
              className={clsx(classes.tab, {
                [classes.tabDisabled]:
                  selectedTab !== TabEnum.conversationSteps,
              })}
              onClick={() => setSelectedTab(TabEnum.conversationSteps)}>
              {'Conversation'}
            </div>
            <div
              className={clsx(classes.tab, {
                [classes.tabDisabled]: selectedTab !== TabEnum.json,
              })}
              onClick={() => setSelectedTab(TabEnum.json)}>
              {'Raw JSON'}
            </div>
          </div>
          {!conversation.data && (
            <div className={classes.loadingWrapper}>
              <ClipLoader color={BLUE_COLOR} />
            </div>
          )}
          {!!conversation.data && (
            <div>
              {selectedTab === TabEnum.conversationSteps && (
                <div>
                  <ConversationProperties
                    conversationProperties={
                      conversation.data.conversationProperties
                    }
                  />
                  <ConversationSteps
                    isLoading={isLoading}
                    conversationId={conversationId}
                    conversationSteps={conversation.data.conversationSteps}
                    conversationOutputs={conversation.data.conversationOutputs}
                  />
                </div>
              )}
              {selectedTab === TabEnum.json && (
                <ReactJson
                  src={conversation.data}
                  theme={'monokai'}
                  collapsed={2}
                  displayDataTypes={false}
                  enableClipboard={false}
                />
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
};

const ComposedBotConversationView: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  connect(conversationSelector),
  connect(readOnlySelector),
  setDisplayName('BotConversationView'),
)(BotConversationView);

export default ComposedBotConversationView;
