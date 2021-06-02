import * as React from 'react';
import styles from './ViewJsonModal.styles';
import '../ModalComponent.styles.scss';
import { compose, pure, setDisplayName } from 'recompose';
import * as moment from 'moment';
import * as _ from 'lodash';
import * as renderIf from 'render-if';
import ModalActionDispatchers from '../../../actions/ModalActionDispatchers';
import { IDetailedDescriptor } from '../../utils/AxiosFunctions';
import BlueButton from '../../Assets/Buttons/BlueButton';
import VersionSelectComponent from '../../Assets/VersionSelectComponent';
import { PACKAGE } from '../../utils/EddiTypes';
import BotsUsingPackage from '../../PackageDetailView/UsedByComponent/BotsUsingPackage';
import PackagesUsingPlugin from '../../PackageDetailView/UsedByComponent/PackagesUsingPlugin';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import { getTypeFromResource } from '../../utils/ApiFunctions';
import Options from '../../Assets/Buttons/Options';
import { readOnlySelector } from '../../../selectors/AuthenticationSelectors';
import { connect } from 'react-redux';

interface IPublicProps {
  descriptor: IDetailedDescriptor;
  data: string;
  usedBy: string[];
  selectVersion(version: number): void;
}

interface IPrivateProps extends IPublicProps {
  readOnly: boolean;
}

interface IState {
  jsonText: string;
}

class ViewJsonContent extends React.Component<IPrivateProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      jsonText: '',
    };
  }

  componentDidMount() {
    this.setJsonText();
  }

  componentDidUpdate(prevProps) {
    if (prevProps !== this.props) {
      this.setJsonText(this.props.data);
    }
  }

  setJsonText(data = this.props.data) {
    this.setState({ jsonText: data });
  }

  openEditJsonModal = () => {
    eddiApiActionDispatchers.fetchJsonSchemaAction(
      getTypeFromResource(this.props.descriptor.resource),
    );
    ModalActionDispatchers.showEditJsonModal(
      this.props.descriptor.resource,
      this.props.data,
    );
  };

  render() {
    const isCurrentVersion =
      this.props.descriptor.currentVersion === this.props.descriptor.version;
    const isPackage =
      !_.isEmpty(this.props.descriptor) &&
      this.props.descriptor.resource.includes(PACKAGE);
    return (
      <div>
        <div style={styles.header}>
          <div style={styles.topHeader}>
            <div style={styles.title}>
              {this.props.descriptor.name || this.props.descriptor.id}
            </div>
            <VersionSelectComponent
              selectedVersion={this.props.descriptor.version}
              currentVersion={this.props.descriptor.currentVersion}
              selectVersion={this.props.selectVersion}
            />
            <div style={styles.centerFlex} />
            <div style={styles.options}>
              <Options
                descriptor={this.props.descriptor}
                data={this.props.data}
              />
            </div>
            <BlueButton
              onClick={this.openEditJsonModal}
              customStyles={styles.button}
              disabled={!isCurrentVersion || this.props.readOnly}
              text={`Edit JSON`}
            />
          </div>
          <div style={styles.bottomHeader}>
            <div style={styles.descriptionContainer}>
              <div style={styles.smallTitle}>{'Description'}</div>
              <div style={styles.smallText}>
                {this.props.descriptor.description}
              </div>
            </div>
            <div style={styles.dateContainer}>
              <div style={styles.smallTitle}>{'Created'}</div>
              <div style={styles.smallText}>
                {moment(this.props.descriptor.createdOn).format('DD.MM.YYYY')}
              </div>
            </div>
            <div style={styles.dateContainer}>
              <div style={styles.smallTitle}>{'Last modified'}</div>
              <div style={styles.smallText}>
                {moment(this.props.descriptor.lastModifiedOn).format(
                  'DD.MM.YYYY',
                )}
              </div>
            </div>
          </div>
          {renderIf(isPackage && !_.isEmpty(this.props.descriptor))(() => (
            <div style={styles.usedInContainer}>
              {'Used in:'}
              <BotsUsingPackage packagePayload={this.props.descriptor} />
            </div>
          ))}
          {renderIf(!isPackage && !_.isEmpty(this.props.descriptor))(() => (
            <div style={styles.usedInContainer}>
              {'Used in:'}
              <PackagesUsingPlugin plugin={this.props.descriptor} />
            </div>
          ))}
        </div>
        <div style={styles.data}>
          {renderIf(!_.isEmpty(this.props.data))(() => (
            <div>{this.state.jsonText}</div>
          ))}
        </div>
      </div>
    );
  }
}

const ComposedViewJsonContent: React.ComponentClass<IPublicProps> = compose<IPrivateProps, IPublicProps>(
  pure,
  connect(readOnlySelector),
  setDisplayName('ViewJsonContent'),
)(ViewJsonContent);

export default ComposedViewJsonContent;
