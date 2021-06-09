import * as _ from 'lodash';
import * as moment from 'moment';
import * as React from 'react';
import { connect } from 'react-redux';
import { compose, pure, setDisplayName } from 'recompose';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import ModalActionDispatchers from '../../../actions/ModalActionDispatchers';
import { readOnlySelector } from '../../../selectors/AuthenticationSelectors';
import BlueButton from '../../Assets/Buttons/BlueButton';
import Options from '../../Assets/Buttons/Options';
import VersionSelectComponent from '../../Assets/VersionSelectComponent';
import BotsUsingPackage from '../../PackageDetailView/UsedByComponent/BotsUsingPackage';
import PackagesUsingPlugin from '../../PackageDetailView/UsedByComponent/PackagesUsingPlugin';
import { getTypeFromResource } from '../../utils/ApiFunctions';
import { IDetailedDescriptor } from '../../utils/AxiosFunctions';
import { PACKAGE } from '../../utils/EddiTypes';
import '../ModalComponent.styles.scss';
import useStyles from './ViewJsonModal.styles';

interface IPublicProps {
  descriptor: IDetailedDescriptor;
  data: string;
  usedBy: string[];
  selectVersion(version: number): void;
}

interface IPrivateProps extends IPublicProps {
  readOnly: boolean;
}

const ViewJsonContent = (props: IPrivateProps) => {
  const [jsonText, setJsonText] = React.useState('');
  const classes = useStyles();

  React.useEffect(() => {
    handleSetJsonText();
  }, [props.descriptor, props.data, props.usedBy, props.readOnly]);

  const handleSetJsonText = () => {
    setJsonText(props.data);
  };

  const openEditJsonModal = () => {
    eddiApiActionDispatchers.fetchJsonSchemaAction(
      getTypeFromResource(props.descriptor.resource),
    );
    ModalActionDispatchers.showEditJsonModal(
      props.descriptor.resource,
      props.data,
    );
  };

  const isCurrentVersion =
    props.descriptor.currentVersion === props.descriptor.version;
  const isPackage =
    !_.isEmpty(props.descriptor) && props.descriptor.resource.includes(PACKAGE);
  return (
    <div>
      <div className={classes.header}>
        <div className={classes.topHeader}>
          <div className={classes.title}>
            {props.descriptor.name || props.descriptor.id}
          </div>
          <VersionSelectComponent
            selectedVersion={props.descriptor.version}
            currentVersion={props.descriptor.currentVersion}
            selectVersion={props.selectVersion}
          />
          <div className={classes.centerFlex} />
          <div className={classes.options}>
            <Options descriptor={props.descriptor} data={props.data} />
          </div>
          <BlueButton
            onClick={openEditJsonModal}
            classes={{ button: classes.button }}
            // disabled={!isCurrentVersion || props.readOnly}
            text={`Edit JSON`}
          />
        </div>
        <div className={classes.bottomHeader}>
          <div className={classes.descriptionContainer}>
            <div className={classes.smallTitle}>{'Description'}</div>
            <div className={classes.smallText}>
              {props.descriptor.description}
            </div>
          </div>
          <div className={classes.dateContainer}>
            <div className={classes.smallTitle}>{'Created'}</div>
            <div className={classes.smallText}>
              {moment(props.descriptor.createdOn).format('DD.MM.YYYY')}
            </div>
          </div>
          <div className={classes.dateContainer}>
            <div className={classes.smallTitle}>{'Last modified'}</div>
            <div className={classes.smallText}>
              {moment(props.descriptor.lastModifiedOn).format('DD.MM.YYYY')}
            </div>
          </div>
        </div>
        {isPackage && !_.isEmpty(props.descriptor) && (
          <div className={classes.usedInContainer}>
            {'Used in:'}
            <BotsUsingPackage packagePayload={props.descriptor} />
          </div>
        )}
        {!isPackage && !_.isEmpty(props.descriptor) && (
          <div className={classes.usedInContainer}>
            {'Used in:'}
            <PackagesUsingPlugin plugin={props.descriptor} />
          </div>
        )}
      </div>
      <div className={classes.data}>
        {!_.isEmpty(props.data) && <div>{jsonText}</div>}
      </div>
    </div>
  );
};

const ComposedViewJsonContent: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  connect(readOnlySelector),
  setDisplayName('ViewJsonContent'),
)(ViewJsonContent);

export default ComposedViewJsonContent;
