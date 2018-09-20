import * as React from 'react';
import * as Modal from 'react-modal';
import styles from './ViewJsonModal.styles';
import '../ModalComponent.styles.scss';
import { Component, compose, pure, setDisplayName } from 'recompose';
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

interface IPrivateProps extends IPublicProps {}

interface IPublicProps {
  descriptor: IDetailedDescriptor;
  data: string;
  usedBy: string[];
  selectVersion(version: number): void;
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

  componentWillReceiveProps(nextProps) {
    this.setJsonText(nextProps.data);
  }

  setJsonText(data = this.props.data) {
    this.setState({ jsonText: data });
  }

  openEditJsonModal = () => {
    ModalActionDispatchers.showEditJsonModal(
      this.props.descriptor.resource,
      this.props.data,
    );
  };

  render() {
    const isCurrentVersion =
      this.props.descriptor.currentVersion === this.props.descriptor.version;
    const isPackage =
      _.isEmpty(this.props.descriptor) &&
      this.props.descriptor.resource.includes(PACKAGE);
    return (
      <div>
        <div style={styles.header}>
          <div style={styles.topHeader}>
            <div style={styles.title}>{this.props.descriptor.name}</div>
            <VersionSelectComponent
              selectedVersion={this.props.descriptor.version}
              currentVersion={this.props.descriptor.currentVersion}
              selectVersion={this.props.selectVersion}
            />
            <div style={styles.centerFlex} />
            <BlueButton
              onClick={this.openEditJsonModal}
              customStyles={styles.button}
              disabled={!isCurrentVersion}
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
          {renderIf(isPackage)(() => (
            <div style={styles.usedInContainer}>
              {'Used in:'}
              <BotsUsingPackage packagePayload={this.props.descriptor} />
            </div>
          ))}
          {renderIf(!isPackage)(() => (
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

const ComposedViewJsonContent: Component<IProps> = compose<IProps>(
  pure,
  setDisplayName('ViewJsonContent'),
)(ViewJsonContent);

export default ComposedViewJsonContent;
