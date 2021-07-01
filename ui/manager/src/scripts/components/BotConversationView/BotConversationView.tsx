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
import {
  getConversation,
  IConversation,
  IConversationData,
} from '../utils/AxiosFunctions';
import { CONVERSATION_READY } from '../utils/helpers/ConversationHelper';
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
  readOnly,
  conversationId,
  conversation: conversationProps,
}: IPrivateProps) => {
  const [selectedTab, setSelectedTab] = React.useState<TabEnum>(
    TabEnum.conversationSteps,
  );

  const [conversation, setConversation] =
    React.useState<IConversationData>(null);
  const [isLoading, setIsLoading] = React.useState<boolean>(false);

  const classes = useStyles();

  React.useEffect(() => {
    setIsLoading(true);
    getConversation(conversationId)
      .then((res: any) => {
        setConversation(res);
        setIsLoading(false);
      })
      .catch(() => {
        setIsLoading(false);
      });
  }, [conversationId]);

  const endConversation = () => {
    modalActionDispatchers.showConfirmationModal(
      `Are you sure you want to end this conversation?`,
      null,
      () => eddiApiActionDispatchers.endConversationAction(conversationId),
    );
  };

  const lastModifiedOn =
    conversation?.conversationSteps?.[
      conversation?.conversationSteps?.length - 1
    ]?.timestamp;
  const createdOn =
    conversation?.conversationSteps?.[0]?.conversationStep?.[0].timestamp;

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
                  historyPush(`/botview/${conversation.botId}`, [
                    `version=${conversation.botVersion}`,
                  ])
                }>
                {conversationProps?.botName || conversation.botId}
              </div>
              <div
                className={
                  classes.botVersion
                }>{`V${conversation.botVersion}`}</div>
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
                  {!!lastModifiedOn && moment(lastModifiedOn).fromNow()}
                </div>
              </div>
              <div className={classes.descriptor}>
                <div className={classes.title}>{'Created on'}</div>
                <div className={classes.descriptorContent}>
                  {!!createdOn && moment(createdOn).format('DD.MM.YYYY')}
                </div>
              </div>
              <div className={classes.descriptor}>
                <div className={classes.title}>{'User id'}</div>
                {!!conversation && (
                  <div className={classes.descriptorContent}>
                    {conversation.userId}
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
          {!conversation && (
            <div className={classes.loadingWrapper}>
              <ClipLoader color={BLUE_COLOR} />
            </div>
          )}
          {!!conversation && (
            <div>
              {selectedTab === TabEnum.conversationSteps && (
                <div>
                  <ConversationProperties
                    conversationProperties={conversation.conversationProperties}
                  />
                  <ConversationSteps
                    isLoading={isLoading}
                    conversationId={conversationId}
                    conversationSteps={conversation.conversationSteps}
                    conversationOutputs={conversation.conversationOutputs}
                  />
                </div>
              )}
              {selectedTab === TabEnum.json && (
                <ReactJson
                  src={conversation}
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
